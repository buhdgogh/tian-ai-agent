package com.tianaiagent.docmcp.query;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 在生成答案前对召回证据做确定性分级，避免让模型自行决定是否有依据。 */
@Service
public class EvidenceAssessmentService {
    private final DocMcpProperties properties;

    public EvidenceAssessmentService(DocMcpProperties properties) {
        this.properties = properties;
    }

    public Assessment assess(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) {
            return new Assessment(Level.NO_RELEVANT_EVIDENCE, false,
                    "知识库中没有召回相关资料，无法依据知识库回答。", "没有召回结果");
        }
        double topScore = sources.stream().mapToDouble(source -> number(source.get("score"))).max().orElse(0);
        if (topScore < properties.getEvidence().getMinimumRelevantScore()) {
            return new Assessment(Level.NO_RELEVANT_EVIDENCE, false,
                    "召回资料与问题相关性过低，无法依据知识库回答。", "最高相关性低于阈值");
        }
        if (hasExplicitConflict(sources)) {
            return new Assessment(Level.CONFLICTING_EVIDENCE, true,
                    "资料存在明确冲突；请分别说明不同来源、版本与适用范围，不得自行消除冲突。",
                    "来源元数据包含相反的 evidence_stance");
        }
        if (topScore < properties.getEvidence().getMinimumSufficientScore()
                || sources.size() < properties.getEvidence().getMinimumSources()) {
            return new Assessment(Level.INSUFFICIENT_EVIDENCE, false,
                    "找到了相关资料，但不足以支持可靠结论；请补充资料或由专业人员复核。",
                    "证据数量或相关性不足");
        }
        return new Assessment(Level.SUFFICIENT_EVIDENCE, true, "", "证据达到生成阈值");
    }

    @SuppressWarnings("unchecked")
    private static boolean hasExplicitConflict(List<Map<String, Object>> sources) {
        Map<String, String> stances = new HashMap<>();
        for (Map<String, Object> source : sources) {
            Object raw = source.get("metadata");
            if (!(raw instanceof Map<?, ?> metadata)) continue;
            String group = text(metadata.get("conflict_group"));
            String stance = text(metadata.get("evidence_stance")).toLowerCase(Locale.ROOT);
            if (group.isBlank() || stance.isBlank()) continue;
            String previous = stances.putIfAbsent(group, stance);
            if (previous != null && !previous.equals(stance)) return true;
        }
        return false;
    }

    private static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    public enum Level {
        NO_RELEVANT_EVIDENCE, INSUFFICIENT_EVIDENCE, CONFLICTING_EVIDENCE, SUFFICIENT_EVIDENCE
    }
    public record Assessment(Level level, boolean allowGeneration, String response, String reason) {}
}
