package com.tianaiagent.infrastructure.gateway.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tianaiagent.types.config.DocMcpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineruClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void extractsMarkdownFromResultZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("result/full.md"));
            zip.write("# 测试文档\n\n正文".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertEquals("# 测试文档\n\n正文", MineruClient.markdownFromZip(bytes.toByteArray()));
    }

    @Test
    void uploadsLocalDocumentAndDownloadsMineruMarkdown() throws Exception {
        byte[] document = "fake-pdf-content".getBytes(StandardCharsets.UTF_8);
        byte[] resultZip = markdownZip("# MinerU结果\n\n表格和公式");
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;
        server.createContext("/api/v4/file-urls/batch", exchange -> respond(exchange, 200,
                ("{\"code\":0,\"data\":{\"batch_id\":\"batch-1\",\"file_urls\":[\"" + baseUrl + "/upload\"]}}")
                        .getBytes(StandardCharsets.UTF_8)));
        server.createContext("/upload", exchange -> {
            uploaded.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, new byte[0]);
        });
        server.createContext("/api/v4/extract-results/batch/batch-1", exchange -> respond(exchange, 200,
                ("{\"code\":0,\"data\":{\"extract_result\":[{\"data_id\":\"item-1\",\"state\":\"done\"," +
                        "\"full_zip_url\":\"" + baseUrl + "/result.zip\"}]}}")
                        .getBytes(StandardCharsets.UTF_8)));
        server.createContext("/result.zip", exchange -> respond(exchange, 200, resultZip));
        server.start();

        DocMcpProperties properties = new DocMcpProperties();
        properties.getMineru().setBaseUrl(baseUrl);
        properties.getMineru().setToken("test-token");
        properties.getMineru().setPollInterval(Duration.ofMillis(1));
        properties.getMineru().setTimeout(Duration.ofSeconds(2));
        MineruClient client = new MineruClient(properties, HttpClient.newHttpClient(), new ObjectMapper());

        String markdown = client.parseUpload(
                new SourceLoader.LoadedSource(document, "report.pdf", "file:///report.pdf"),
                new MineruClient.ParseOptions("item-1", "vlm", true, true, true, "ch"));

        assertEquals("# MinerU结果\n\n表格和公式", markdown);
        assertTrue(java.util.Arrays.equals(document, uploaded.get()));
    }

    private static byte[] markdownZip(String markdown) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("result/full.md"));
            zip.write(markdown.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
