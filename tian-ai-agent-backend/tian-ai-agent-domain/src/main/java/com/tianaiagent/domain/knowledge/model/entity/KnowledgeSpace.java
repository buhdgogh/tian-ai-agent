package com.tianaiagent.domain.knowledge.model.entity;

import com.tianaiagent.domain.knowledge.model.valobj.MedicalDocumentMetadata;

import java.util.Map;
import java.util.UUID;

public record KnowledgeSpace(String id, String name, boolean medical, boolean deleting) {
    public static final String MEDICAL_ID = "medical";
    public static final KnowledgeSpace MEDICAL = new KnowledgeSpace(MEDICAL_ID, "医疗知识库", true);

    public KnowledgeSpace(String id, String name, boolean medical) { this(id, name, medical, false); }

    public static KnowledgeSpace create(String name, boolean medical) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new IllegalArgumentException("知识库空间名称必须为 1 到 100 个字符");
        }
        return new KnowledgeSpace(UUID.randomUUID().toString().replace("-", ""), name.strip(), medical);
    }

    public static String requireId(String id) {
        if (id == null || !id.matches("medical|[a-f0-9]{32}")) {
            throw new IllegalArgumentException("请选择有效的知识库空间");
        }
        return id;
    }

    public static String optionalId(String id) {
        return id == null || id.isBlank() ? null : requireId(id);
    }

    public boolean allowsDocument(Map<String, Object> metadata) {
        return !medical || MedicalDocumentMetadata.published(metadata);
    }
}
