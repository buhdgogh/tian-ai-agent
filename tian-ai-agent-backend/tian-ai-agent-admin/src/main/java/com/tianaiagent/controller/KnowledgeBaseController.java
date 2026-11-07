package com.tianaiagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianaiagent.config.KnowledgeBaseRateLimiter;
import com.tianaiagent.service.KnowledgeBaseService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge-base")
public class KnowledgeBaseController {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private KnowledgeBaseRateLimiter rateLimiter;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file,
                                        @RequestParam String issuer,
                                        @RequestParam String version,
                                        @RequestParam String publishedAt,
                                        @RequestParam String audience,
                                        @RequestParam String documentType,
                                        @RequestParam(defaultValue = "ACTIVE") String effectiveStatus,
                                        @RequestParam(defaultValue = "APPROVED") String reviewStatus,
                                        HttpServletRequest request) {
        // Redis ZSET + Lua 原子脚本，通过ip进行滑动窗口限流，limitResult → 当前滑动窗口中空闲位置的数量
        KnowledgeBaseRateLimiter.RateLimitResult limitResult = rateLimiter.tryAcquire(request);
        if (!limitResult.allowed()) {
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(limitResult.retryAfterSeconds()))
                    .body(Map.of(
                            "message", "上传请求过于频繁，请在 " + limitResult.retryAfterSeconds() + " 秒后重试"
                    ));
        }

        var metadata = new KnowledgeBaseService.MedicalDocumentMetadata(
                issuer, version, publishedAt, audience, effectiveStatus, reviewStatus, documentType);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(knowledgeBaseService.saveFile(file, metadata));
    }

    @GetMapping("/files")
    public List<Map<String, Object>> listFiles() {
        return knowledgeBaseService.listFiles();
    }

    @DeleteMapping("/files/{id}")
    public Map<String, String> deleteFile(@PathVariable String id) {
        knowledgeBaseService.deleteFile(id);
        return Map.of("message", "删除成功");
    }

    /**
     * 检索增强问答（LlamaIndex 多层级 chunk 检索 + 生成答案）
     */
    @PostMapping("/query")
    public String query(@RequestBody QueryRequest request) {
        return knowledgeBaseService.query(request.question());
    }

    @PostMapping("/query-with-sources")
    public JsonNode queryWithSources(@RequestBody QueryRequest request) {
        return knowledgeBaseService.queryWithSources(
                request.question(), request.topK());
    }

    @PostMapping("/retrieve")
    public JsonNode retrieve(@RequestBody QueryRequest request) {
        if (request.retrievalMode() != null && !request.retrievalMode().isBlank()
                && !List.of("hybrid", "keyword", "dense").contains(request.retrievalMode())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "retrievalMode只支持hybrid、keyword、dense");
        }
        return knowledgeBaseService.retrieve(request.question(), request.topK(), request.retrievalMode());
    }

    /** 检索问答请求体 */
    public record QueryRequest(String question, Integer topK, String retrievalMode) {
        public QueryRequest(String question, Integer topK) { this(question, topK, null); }
    }
}
