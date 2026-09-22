package com.tianaiagent.docmcp.index;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
public class HierarchicalChunker {
    private final DocMcpProperties properties;

    public HierarchicalChunker(DocMcpProperties properties) {
        this.properties = properties;
    }

    public List<ChunkDraft> split(String docId, String markdown) {
        String cleaned = markdown == null ? "" : markdown.replace("\r\n", "\n").trim();
        if (cleaned.isBlank()) throw new IllegalArgumentException("文档正文为空");
        List<Integer> sizes = properties.getChunkSizes().stream().filter(value -> value != null && value > 0).toList();
        if (sizes.isEmpty()) throw new IllegalStateException("chunk-sizes 不能为空");
        List<ChunkDraft> result = new ArrayList<>();
        createLevel(docId, cleaned, null, 0, sizes, result, new int[]{0});
        return result;
    }

    private void createLevel(String docId, String text, String parentId, int level,
                             List<Integer> sizes, List<ChunkDraft> output, int[] ordinal) {
        int size = sizes.get(level);
        List<String> parts = splitText(text, size, Math.min(properties.getChunkOverlap(), size / 4));
        for (String part : parts) {
            String id = sha256(docId + "\n" + level + "\n" + ordinal[0] + "\n" + part);
            boolean leaf = level == sizes.size() - 1;
            ChunkDraft draft = new ChunkDraft(id, parentId, level, leaf, ordinal[0]++, part);
            output.add(draft);
            if (!leaf) createLevel(docId, part, id, level + 1, sizes, output, ordinal);
        }
    }

    static List<String> splitText(String text, int maxCodePoints, int overlap) {
        List<String> result = new ArrayList<>();
        int total = text.codePointCount(0, text.length());
        int startCp = 0;
        while (startCp < total) {
            int endCp = Math.min(total, startCp + maxCodePoints);
            int start = text.offsetByCodePoints(0, startCp);
            int end = text.offsetByCodePoints(0, endCp);
            if (endCp < total) {
                int boundary = bestBoundary(text, start, end);
                if (boundary > start + Math.max(32, (end - start) / 2)) {
                    end = boundary;
                    endCp = text.codePointCount(0, end);
                }
            }
            String part = text.substring(start, end).trim();
            if (!part.isBlank()) result.add(part);
            if (endCp >= total) break;
            startCp = Math.max(startCp + 1, endCp - overlap);
        }
        return result;
    }

    private static int bestBoundary(String text, int start, int end) {
        for (String marker : List.of("\n\n", "\n#", "。", ". ", "；", "; ")) {
            int candidate = text.lastIndexOf(marker, end);
            if (candidate >= start) return candidate + marker.length();
        }
        return end;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record ChunkDraft(String nodeId, String parentId, int level, boolean leaf,
                             int ordinal, String text) {}
}
