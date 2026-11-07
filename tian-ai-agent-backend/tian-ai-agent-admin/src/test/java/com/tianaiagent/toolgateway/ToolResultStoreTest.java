package com.tianaiagent.toolgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultStoreTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private ToolResultStore store(long quota) {
        return new ToolResultStore(mapper, directory.toString(), 512, 1024, 24, quota);
    }

    @Test
    void smallResultIsUnchangedAndDoesNotCreateFiles() {
        ToolResultStore store = store(1_000_000);
        assertEquals("{\"ok\":true}", store.limit("chat1", "search", "{\"ok\":true}", 512));
        assertEquals(0, directory.toFile().list().length);
    }

    @Test
    void externalizesUnicodeAndEscapedJsonAndPagesBackToExactOriginal() throws Exception {
        ToolResultStore store = store(1_000_000);
        String original = "医学证据😀\\\"\n".repeat(600);
        String bounded = store.limit("chat1", "search", original, 512);
        JsonNode envelope = mapper.readTree(bounded);
        assertTrue(envelope.path("stored").asBoolean());
        assertTrue(store.countTokens(bounded) <= 512);
        String id = envelope.path("resultId").asText();
        ToolResultStore.StoredResult stored = mapper.readValue(directory.resolve(id + ".json").toFile(),
                ToolResultStore.StoredResult.class);
        assertEquals(original, stored.text());
        StringBuilder restored = new StringBuilder();
        int offset = 0;
        while (offset < original.length()) {
            String response = store.read("chat1", id, offset, 256);
            assertTrue(store.countTokens(response) <= 256);
            JsonNode page = mapper.readTree(response);
            restored.append(page.path("content").asText());
            int next = page.path("nextOffset").asInt();
            assertTrue(next > offset);
            offset = next;
            assertEquals(offset < original.length(), page.path("hasMore").asBoolean());
        }
        assertEquals(original, restored.toString());
        // 重建服务后仍可读取，结果不依赖 JVM 内存缓存。
        assertTrue(store(1_000_000).read("chat1", id, 0, 256).contains(id));
        assertThrows(IllegalArgumentException.class, () -> store.read("chat2", id, 0, 256));
        assertThrows(IllegalArgumentException.class, () -> store.read("chat1", "../application.yml", 0, 256));
        assertThrows(IllegalArgumentException.class, () -> store.read("chat1", id, -1, 256));
    }

    @Test
    void quotaFailureReturnsBoundedPreviewWithoutClaimingResultIsStored() throws Exception {
        ToolResultStore store = store(1);
        String response = store.limit("chat1", "search", "large result ".repeat(2000), 512);
        JsonNode envelope = mapper.readTree(response);
        assertFalse(envelope.path("stored").asBoolean());
        assertFalse(envelope.has("resultId"));
        assertTrue(store.countTokens(response) <= 512);
        assertEquals(0, directory.toFile().list().length);
    }

    @Test
    void expiredResultsCannotBeRead() throws Exception {
        ToolResultStore store = store(1_000_000);
        String id = "0123456789abcdef0123456789abcdef";
        Files.writeString(directory.resolve(id + ".json"), mapper.writeValueAsString(
                new ToolResultStore.StoredResult("chat1", "search", 0, "expired")));
        assertThrows(IllegalArgumentException.class, () -> store.read("chat1", id, 0, 256));
    }
}
