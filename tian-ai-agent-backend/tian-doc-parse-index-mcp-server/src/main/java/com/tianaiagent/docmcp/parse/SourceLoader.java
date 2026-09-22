package com.tianaiagent.docmcp.parse;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Component
public class SourceLoader {
    private final HttpClient http;
    private final DocMcpProperties properties;

    public SourceLoader(HttpClient http, DocMcpProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    public LoadedSource load(String source) {
        try {
            if (isWindowsAbsolutePath(source)) return loadLocal(Path.of(source));
            URI uri = URI.create(source);
            if (uri.getScheme() == null || "file".equalsIgnoreCase(uri.getScheme())) {
                Path path = uri.getScheme() == null ? Path.of(source) : Path.of(uri);
                return loadLocal(path);
            }
            if (!List.of("http", "https").contains(uri.getScheme().toLowerCase())) {
                throw new IllegalArgumentException("仅支持本地文件、HTTP和HTTPS来源");
            }
            rejectPrivateAddress(uri);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("下载文档失败 HTTP " + response.statusCode());
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > properties.getMaxSourceBytes()) throw new IllegalArgumentException("文件超过大小限制");
            try (InputStream input = response.body()) {
                byte[] bytes = readLimited(input, properties.getMaxSourceBytes());
                String path = uri.getPath();
                String name = path == null || path.isBlank() ? "remote-document" : Path.of(path).getFileName().toString();
                return new LoadedSource(bytes, name, publicUri(uri));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("文档下载被中断", e);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("读取文档失败: " + e.getMessage(), e);
        }
    }

    private LoadedSource loadLocal(Path path) throws Exception {
        if (!properties.isAllowLocalFiles()) throw new IllegalArgumentException("本地文件解析已禁用");
        Path normalized = path.toAbsolutePath().normalize();
        long size = Files.size(normalized);
        if (size > properties.getMaxSourceBytes()) throw new IllegalArgumentException("文件超过大小限制");
        return new LoadedSource(Files.readAllBytes(normalized), normalized.getFileName().toString(),
                normalized.toUri().toString());
    }

    private static boolean isWindowsAbsolutePath(String source) {
        return source != null && source.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static byte[] readLimited(InputStream input, long maximum) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long count = 0;
        for (int read; (read = input.read(buffer)) >= 0;) {
            count += read;
            if (count > maximum) throw new IllegalArgumentException("文件超过大小限制");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void rejectPrivateAddress(URI uri) throws Exception {
        if (uri.getHost() == null) throw new IllegalArgumentException("URL缺少主机名");
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("不允许访问内网或本机地址: " + uri.getHost());
            }
        }
    }

    private static String publicUri(URI uri) {
        try { return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null).toString(); }
        catch (Exception e) { return uri.getScheme() + "://" + uri.getAuthority() + uri.getPath(); }
    }

    public record LoadedSource(byte[] bytes, String fileName, String publicUri) {}
}
