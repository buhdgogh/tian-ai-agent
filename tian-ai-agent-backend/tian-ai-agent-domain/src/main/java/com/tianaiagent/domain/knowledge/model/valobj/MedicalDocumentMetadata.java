package com.tianaiagent.domain.knowledge.model.valobj;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

public record MedicalDocumentMetadata(String issuer, String version, String publishedAt,
                                      String audience, String effectiveStatus,
                                      String reviewStatus, String documentType) {
    public static MedicalDocumentMetadata approvedFrom(Map<String, Object> metadata) {
        if (metadata == null) return approvedForIndex(null);
        return approvedForIndex(new MedicalDocumentMetadata(text(metadata, "issuer"), text(metadata, "version"),
                text(metadata, "publishedAt"), text(metadata, "audience"), text(metadata, "effectiveStatus"),
                text(metadata, "reviewStatus"), text(metadata, "documentType")));
    }

    public static boolean published(Map<String, Object> metadata) {
        try {
            approvedFrom(metadata);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String text(Map<String, Object> metadata, String key) {
        return metadata.get(key) instanceof String value ? value : null;
    }

    public static MedicalDocumentMetadata approvedForIndex(MedicalDocumentMetadata value) {
        if (value == null) throw new IllegalArgumentException("医疗文档元数据不能为空");
        require(value.issuer(), "issuer");
        require(value.version(), "version");
        require(value.publishedAt(), "publishedAt");
        require(value.audience(), "audience");
        require(value.documentType(), "documentType");
        try {
            LocalDate.parse(value.publishedAt());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("publishedAt必须为YYYY-MM-DD");
        }
        if (!"ACTIVE".equalsIgnoreCase(value.effectiveStatus())) {
            throw new IllegalArgumentException("只有effectiveStatus=ACTIVE的文档可以进入当前检索库");
        }
        if (!"APPROVED".equalsIgnoreCase(value.reviewStatus())) {
            throw new IllegalArgumentException("只有reviewStatus=APPROVED的文档可以进入当前检索库");
        }
        return new MedicalDocumentMetadata(value.issuer().trim(), value.version().trim(), value.publishedAt().trim(),
                value.audience().trim(), "ACTIVE", "APPROVED", value.documentType().trim());
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    }
}
