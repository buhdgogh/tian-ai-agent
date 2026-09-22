package com.tianaiagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.medical.MedicalEvidenceClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/** 权威医学文献、引用和临床试验只读查询工具。 */
public class MedicalEvidenceTool {
    private final MedicalEvidenceClient evidence;
    private final ObjectMapper mapper;

    public MedicalEvidenceTool(MedicalEvidenceClient evidence, ObjectMapper mapper) {
        this.evidence = evidence;
        this.mapper = mapper;
    }

    @Tool(description = "检索PubMed权威医学文献，返回PMID、标题、摘要、期刊、日期、DOI和文章类型。需要最新医学研究或外部证据时优先使用。")
    public String searchPubMed(
            @ToolParam(description = "PubMed检索式，可包含疾病、干预、人群及日期等限定，最长500字符") String query,
            @ToolParam(description = "返回数量，范围1到10") Integer maxResults) {
        return json(() -> evidence.searchPubMed(query, number(maxResults, 5)));
    }

    @Tool(description = "根据PMID获取PubMed文献详情与摘要。不得将文献存在等同于其支持某项医疗结论。")
    public String getPubMedArticle(
            @ToolParam(description = "纯数字PMID") String pmid) {
        return json(() -> evidence.getPubMedArticle(pmid));
    }

    @Tool(description = "核验PMID及预期标题是否与PubMed书目信息一致，用于Reviewer阶段检查引用真实性；不负责判断文献是否支持具体医疗主张。")
    public String verifyPubMedCitation(
            @ToolParam(description = "纯数字PMID") String pmid,
            @ToolParam(description = "需要核对的文献标题；未知时传空字符串") String expectedTitle) {
        return json(() -> evidence.verifyPubMedCitation(pmid, expectedTitle));
    }

    @Tool(description = "查询ClinicalTrials.gov临床试验，返回NCT编号、标题、状态、分期、疾病和干预措施。结果仅用于信息检索，不构成入组或治疗建议。")
    public String searchClinicalTrials(
            @ToolParam(description = "疾病或研究条件，最长300字符") String condition,
            @ToolParam(description = "干预措施或药物名称；不限时传空字符串") String intervention,
            @ToolParam(description = "状态，如RECRUITING、COMPLETED；不限时传空字符串") String recruitmentStatus,
            @ToolParam(description = "返回数量，范围1到10") Integer maxResults) {
        return json(() -> evidence.searchClinicalTrials(
                condition, intervention, recruitmentStatus, number(maxResults, 5)));
    }

    private String json(Query query) {
        try {
            return mapper.writeValueAsString(Map.of("ok", true, "data", query.execute()));
        } catch (Exception e) {
            try {
                return mapper.writeValueAsString(Map.of("ok", false, "error", rootMessage(e)));
            } catch (Exception ignored) {
                return "{\"ok\":false,\"error\":\"医疗证据查询失败\"}";
            }
        }
    }

    private static int number(Integer value, int fallback) { return value == null ? fallback : value; }
    private static String rootMessage(Throwable value) {
        Throwable current = value;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
    @FunctionalInterface private interface Query { Map<String, Object> execute(); }
}
