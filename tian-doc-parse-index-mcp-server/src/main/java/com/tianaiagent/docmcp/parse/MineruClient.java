package com.tianaiagent.docmcp.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.docmcp.config.DocMcpProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class MineruClient {
    private final DocMcpProperties properties;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public MineruClient(DocMcpProperties properties, HttpClient http, ObjectMapper mapper) {
        this.properties = properties;
        this.http = http;
        this.mapper = mapper;
    }

    public boolean isAvailable() {
        return properties.getMineru().isEnabled()
                && properties.getMineru().getToken() != null
                && !properties.getMineru().getToken().isBlank();
    }

    public boolean canParseRemote(String source) {
        return isAvailable()
                && (source.startsWith("http://") || source.startsWith("https://"));
    }

    public String parseRemote(String source, ParseOptions options) {
        var mineru = properties.getMineru();
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("url", source);
        file.put("data_id", options.dataId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("files", List.of(file));
        payload.put("model_version", options.model());
        payload.put("is_ocr", options.ocr());
        payload.put("enable_formula", options.formula());
        payload.put("enable_table", options.table());
        payload.put("language", options.language());
        String batchId = batchId(request("POST", "/api/v4/extract/task/batch", payload));
        return awaitMarkdown(batchId, options.dataId());
    }

    /** 使用 MinerU 官方预签名地址上传本地文件，上传完成后任务会自动开始。 */
    public String parseUpload(SourceLoader.LoadedSource source, ParseOptions options) {
        if (!isAvailable()) throw new IllegalStateException("MinerU未启用或未配置Token");
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("name", source.fileName());
        file.put("data_id", options.dataId());
        Map<String, Object> payload = commonPayload(options);
        payload.put("files", List.of(file));

        JsonNode submitted = request("POST", "/api/v4/file-urls/batch", payload);
        String batchId = batchId(submitted);
        JsonNode urls = submitted.path("data").path("file_urls");
        if (!urls.isArray() || urls.isEmpty() || urls.get(0).asText("").isBlank()) {
            throw new IllegalStateException("MinerU上传响应缺少file_urls: " + submitted);
        }
        upload(urls.get(0).asText(), source.bytes());
        return awaitMarkdown(batchId, options.dataId());
    }

    private Map<String, Object> commonPayload(ParseOptions options) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model_version", options.model());
        payload.put("is_ocr", options.ocr());
        payload.put("enable_formula", options.formula());
        payload.put("enable_table", options.table());
        payload.put("language", options.language());
        return payload;
    }

    private String batchId(JsonNode submitted) {
        String batchId = submitted.path("data").path("batch_id").asText("");
        if (batchId.isBlank()) throw new IllegalStateException("MinerU提交响应缺少batch_id: " + submitted);
        return batchId;
    }

    private String awaitMarkdown(String batchId, String dataId) {
        var mineru = properties.getMineru();
        long deadline = System.nanoTime() + mineru.getTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode status = request("GET", "/api/v4/extract-results/batch/" + batchId, null);
            JsonNode results = status.path("data").path("extract_result");
            if (results.isArray() && !results.isEmpty()) {
                JsonNode item = findResult(results, dataId);
                String state = item.path("state").asText("").toLowerCase();
                if ("failed".equals(state)) throw new IllegalStateException("MinerU解析失败: " + item.path("err_msg").asText(item.toString()));
                if ("done".equals(state)) {
                    String zipUrl = item.path("full_zip_url").asText(item.path("zip_url").asText(""));
                    if (zipUrl.isBlank()) throw new IllegalStateException("MinerU完成响应缺少结果包地址: " + item);
                    return markdownFromZip(download(zipUrl));
                }
            }
            try { Thread.sleep(Math.max(200, mineru.getPollInterval().toMillis())); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("MinerU轮询被中断", e); }
        }
        throw new IllegalStateException("等待MinerU解析超时: batch_id=" + batchId);
    }

    private static JsonNode findResult(JsonNode results, String dataId) {
        if (dataId != null && !dataId.isBlank()) {
            for (JsonNode result : results) {
                if (dataId.equals(result.path("data_id").asText())) return result;
            }
        }
        return results.get(0);
    }

    private void upload(String url, byte[] bytes) {
        try {
            HttpRequest upload = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<String> response = http.send(upload,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("MinerU文件上传失败 HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MinerU文件上传被中断", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("MinerU文件上传失败: " + e.getMessage(), e);
        }
    }

    private JsonNode request(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(trimSlash(properties.getMineru().getBaseUrl()) + path))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + properties.getMineru().getToken())
                    .header("Content-Type", "application/json");
            if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            else builder.GET();
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("MinerU HTTP " + response.statusCode() + ": " + response.body());
            JsonNode json = mapper.readTree(response.body());
            if (json.has("code") && json.path("code").asInt(0) != 0) throw new IllegalStateException("MinerU业务错误: " + json);
            return json;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("MinerU请求被中断", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("MinerU请求失败: " + e.getMessage(), e);
        }
    }

    private byte[] download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("MinerU结果包下载失败 HTTP " + response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("MinerU结果下载被中断", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("MinerU结果下载失败: " + e.getMessage(), e);
        }
    }

    static String markdownFromZip(byte[] zip) {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            String fallback = null;
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".md")) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                input.transferTo(output);
                String markdown = output.toString(StandardCharsets.UTF_8);
                if (entry.getName().endsWith("full.md") || entry.getName().endsWith("/full.md")) return markdown;
                if (fallback == null) fallback = markdown;
            }
            if (fallback != null) return fallback;
            throw new IllegalStateException("MinerU结果包中没有Markdown文件");
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("MinerU结果包解析失败: " + e.getMessage(), e);
        }
    }

    private static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    public record ParseOptions(String dataId, String model, boolean ocr, boolean formula, boolean table, String language) {}
}
