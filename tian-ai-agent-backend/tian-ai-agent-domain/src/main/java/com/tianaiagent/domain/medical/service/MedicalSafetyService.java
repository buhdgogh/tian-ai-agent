package com.tianaiagent.domain.medical.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 在调用模型前执行的确定性医疗风险分诊，不依赖大模型自行识别急症。 */
@Service
public class MedicalSafetyService {
    private static final Map<String, String> EMERGENCY_SIGNALS = Map.ofEntries(
            Map.entry("胸痛", "持续或剧烈胸痛"),
            Map.entry("呼吸困难", "呼吸困难"),
            Map.entry("喘不上气", "严重呼吸困难"),
            Map.entry("意识不清", "意识状态异常"),
            Map.entry("昏迷", "意识丧失"),
            Map.entry("抽搐", "抽搐"),
            Map.entry("口角歪斜", "疑似卒中征象"),
            Map.entry("一侧无力", "疑似卒中征象"),
            Map.entry("严重过敏", "疑似严重过敏反应"),
            Map.entry("喉咙肿", "疑似气道受累"),
            Map.entry("服药过量", "疑似药物过量"),
            Map.entry("药物中毒", "疑似药物中毒"),
            Map.entry("大量出血", "大量出血"),
            Map.entry("想自杀", "自伤或自杀风险"),
            Map.entry("不想活", "自伤或自杀风险")
    );

    public RiskAssessment assess(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        EMERGENCY_SIGNALS.forEach((term, label) -> {
            if (normalized.contains(term) && !matched.contains(label)) matched.add(label);
        });
        if (!matched.isEmpty()) {
            return new RiskAssessment("EMERGENCY", true, List.copyOf(matched), emergencyMessage(matched));
        }
        boolean medication = normalized.contains("用药") || normalized.contains("剂量")
                || normalized.contains("处方") || normalized.contains("药物") || normalized.contains("吃多少");
        return new RiskAssessment(medication ? "HIGH" : "ROUTINE", false, List.of(), "");
    }

    private String emergencyMessage(List<String> signals) {
        return "检测到可能需要紧急处理的情况（" + String.join("、", signals) + "）。"
                + "如果症状正在发生，请立即拨打 120 或前往最近的急诊；不要等待在线回复，也不要自行驾车。"
                + "如涉及服药过量或中毒，请保留药品包装并告知急救人员。";
    }

    public record RiskAssessment(String level, boolean emergency, List<String> signals, String message) {}
}
