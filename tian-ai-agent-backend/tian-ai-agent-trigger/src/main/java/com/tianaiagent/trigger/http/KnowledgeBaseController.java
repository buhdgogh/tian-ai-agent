package com.tianaiagent.trigger.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianaiagent.api.knowledge.QueryRequest;
import com.tianaiagent.domain.agent.service.AgentRunAudit;
import com.tianaiagent.api.knowledge.CreateKnowledgeSpaceRequest;
import com.tianaiagent.api.knowledge.DeleteKnowledgeSpaceRequest;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import com.tianaiagent.domain.knowledge.model.valobj.MedicalDocumentMetadata;
import com.tianaiagent.domain.knowledge.adapter.port.UploadRateLimiter;
import com.tianaiagent.domain.knowledge.service.KnowledgeBaseService;
import com.tianaiagent.domain.task.model.aggregate.TaskRecord;
import org.springframework.web.server.ResponseStatusException;
import com.tianaiagent.domain.knowledge.service.WikiGraphService;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tianaiagent.trigger.security.RequestIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge-base")
public class KnowledgeBaseController {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private UploadRateLimiter rateLimiter;

    @Resource
    private ObjectProvider<WikiGraphService> wiki;

    @Resource
    private AgentRunAudit runAudit;

    @GetMapping("/wiki-graph")
    public WikiGraphService.Graph wikiGraph(@RequestParam String spaceId) {
        knowledgeBaseService.requireMcp();
        return wiki.getObject().load(spaceId);
    }

    @GetMapping("/wiki-pages/{pageId}")
    public WikiGraphService.PageContent wikiPage(@RequestParam String spaceId, @PathVariable String pageId) {
        knowledgeBaseService.requireMcp();
        return wiki.getObject().page(spaceId, pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wiki 页面不存在或原始资料已更新"));
    }

    @PostMapping("/wiki-compile")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskRecord compileWiki(@RequestParam String spaceId,
                                  @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        identity.requireAdmin();
        knowledgeBaseService.requireMcp();
        return wiki.getObject().submit(spaceId, identity.ownerId());
    }

    @GetMapping("/spaces")
    public List<KnowledgeSpace> spaces() { return knowledgeBaseService.listSpaces(); }

    @PostMapping("/spaces")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeSpace createSpace(@RequestBody CreateKnowledgeSpaceRequest request,
                                      @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        identity.requireAdmin();
        return knowledgeBaseService.createSpace(request.name(), request.medical());
    }

    @DeleteMapping("/spaces/{spaceId}")
    public ResponseEntity<?> deleteSpace(@PathVariable String spaceId,
                                         @RequestBody(required = false) DeleteKnowledgeSpaceRequest request,
                                         @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        identity.requireAdmin();
        if (request == null || !identity.ownerId().equals(request.accountName())) {
            return ResponseEntity.badRequest().body(Map.of("message", "输入的账号名与当前登录账号不一致"));
        }
        try {
            knowledgeBaseService.deleteSpace(spaceId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (KnowledgeBaseService.SpaceUploadInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("删除知识库失败，等待重试 spaceId={}", spaceId, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "知识库清理未完成，请稍后重新确认删除"));
        }
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file,
                                        @RequestParam String spaceId,
                                        @RequestParam(required = false) String issuer,
                                        @RequestParam(required = false) String version,
                                        @RequestParam(required = false) String publishedAt,
                                        @RequestParam(required = false) String audience,
                                        @RequestParam(required = false) String documentType,
                                        @RequestParam(defaultValue = "ACTIVE") String effectiveStatus,
                                        @RequestParam(defaultValue = "APPROVED") String reviewStatus,
                                        @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) throws IOException {
        identity.requireAdmin();
        knowledgeBaseService.requireMcp();
        UploadRateLimiter.RateLimitResult limitResult = rateLimiter.tryAcquire(identity.ownerId());
        if (!limitResult.allowed()) {
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(limitResult.retryAfterSeconds()))
                    .body(Map.of(
                            "message", "上传请求过于频繁，请在 " + limitResult.retryAfterSeconds() + " 秒后重试"
                    ));
        }

        var metadata = new MedicalDocumentMetadata(
                issuer, version, publishedAt, audience, effectiveStatus, reviewStatus, documentType);
        try (var contents = file.getInputStream()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                    knowledgeBaseService.saveFile(spaceId, file.getOriginalFilename(), file.getSize(), contents, metadata, identity.ownerId()));
        } catch (KnowledgeBaseService.DuplicateDocumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", e.getMessage(), "code", "DUPLICATE_DOCUMENT", "documentId", e.documentId()));
        }
    }

    @GetMapping("/files")
    public List<Map<String, Object>> listFiles(@RequestParam String spaceId,
                                               @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        identity.requireAdmin();
        return knowledgeBaseService.listFiles(spaceId);
    }

    @DeleteMapping("/files/{id}")
    public Map<String, String> deleteFile(@PathVariable String id,
                                          @RequestParam String spaceId,
                                          @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        identity.requireAdmin();
        knowledgeBaseService.deleteFile(spaceId, id);
        return Map.of("message", "删除成功");
    }

    /** 检索多层级知识片段并生成带证据的回答。 */
    @PostMapping("/query")
    public String query(@RequestBody QueryRequest request,
                        @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return runAudit.call(identity.ownerId(), "RAG_CHAT",
                () -> knowledgeBaseService.query(request.spaceId(), request.question()));
    }

    @PostMapping("/query-with-sources")
    public JsonNode queryWithSources(@RequestBody QueryRequest request,
                                    @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return runAudit.call(identity.ownerId(), "RAG_CHAT",
                () -> knowledgeBaseService.queryWithSources(request.spaceId(), request.question(), request.topK()));
    }

    @PostMapping("/retrieve")
    public JsonNode retrieve(@RequestBody QueryRequest request) {
        return knowledgeBaseService.retrieve(request.spaceId(), request.question(), request.topK());
    }

}
