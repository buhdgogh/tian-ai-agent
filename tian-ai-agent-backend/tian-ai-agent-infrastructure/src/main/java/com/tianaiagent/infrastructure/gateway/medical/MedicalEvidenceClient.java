package com.tianaiagent.infrastructure.gateway.medical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 只访问 PubMed/PMC 与 ClinicalTrials.gov 官方 API 的只读客户端。 */
@Component
public class MedicalEvidenceClient {
    private static final String NCBI_BASE = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/";
    private static final String TRIALS_BASE = "https://clinicaltrials.gov/api/v2/studies";
    private static final Set<String> TRIAL_STATUSES = Set.of(
            "RECRUITING", "NOT_YET_RECRUITING", "ENROLLING_BY_INVITATION", "ACTIVE_NOT_RECRUITING",
            "COMPLETED", "SUSPENDED", "TERMINATED", "WITHDRAWN", "UNKNOWN");

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String ncbiApiKey;
    private final String ncbiEmail;
    private final Object rateLock = new Object();
    private long lastNcbiRequestNanos;

    public MedicalEvidenceClient(ObjectMapper mapper,
                                 @Value("${medical-evidence.ncbi.api-key:}") String ncbiApiKey,
                                 @Value("${medical-evidence.ncbi.email:}") String ncbiEmail) {
        this.mapper = mapper;
        this.ncbiApiKey = ncbiApiKey == null ? "" : ncbiApiKey.trim();
        this.ncbiEmail = ncbiEmail == null ? "" : ncbiEmail.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> searchPubMed(String query, int requestedLimit) {
        String safeQuery = required(query, "query", 500);
        int limit = bounded(requestedLimit, 1, 10);
        JsonNode search = getJson(ncbiUri("esearch.fcgi", Map.of(
                "db", "pubmed", "term", safeQuery, "retmode", "json",
                "retmax", String.valueOf(limit), "sort", "relevance")));
        List<String> ids = new ArrayList<>();
        search.path("esearchresult").path("idlist").forEach(value -> ids.add(value.asText()));
        List<Map<String, Object>> articles = ids.isEmpty() ? List.of() : fetchPubMed(ids);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", safeQuery);
        result.put("count", articles.size());
        result.put("articles", articles);
        result.put("source", "PubMed/NCBI");
        return result;
    }

    public Map<String, Object> getPubMedArticle(String pmid) {
        String safePmid = validPmid(pmid);
        List<Map<String, Object>> articles = fetchPubMed(List.of(safePmid));
        if (articles.isEmpty()) return Map.of("found", false, "pmid", safePmid, "source", "PubMed/NCBI");
        Map<String, Object> result = new LinkedHashMap<>(articles.getFirst());
        result.put("found", true);
        return result;
    }

    public Map<String, Object> verifyPubMedCitation(String pmid, String expectedTitle) {
        Map<String, Object> article = getPubMedArticle(pmid);
        if (!Boolean.TRUE.equals(article.get("found"))) return article;
        String actual = String.valueOf(article.getOrDefault("title", ""));
        double similarity = titleSimilarity(expectedTitle, actual);
        Map<String, Object> result = new LinkedHashMap<>(article);
        result.put("expected_title", expectedTitle == null ? "" : expectedTitle.trim());
        result.put("title_similarity", Math.round(similarity * 1000.0) / 1000.0);
        result.put("bibliographic_match", expectedTitle == null || expectedTitle.isBlank() || similarity >= 0.65);
        result.put("scope", "仅核验文献身份与书目信息，不代表文献结论支持具体医疗主张");
        return result;
    }

    public Map<String, Object> searchClinicalTrials(String condition, String intervention,
                                                     String recruitmentStatus, int requestedLimit) {
        String safeCondition = required(condition, "condition", 300);
        String safeIntervention = optional(intervention, 300);
        String safeStatus = optional(recruitmentStatus, 40).toUpperCase(Locale.ROOT).replace('-', '_');
        if (!safeStatus.isBlank() && !TRIAL_STATUSES.contains(safeStatus)) {
            throw new IllegalArgumentException("不支持的试验状态: " + recruitmentStatus);
        }
        int limit = bounded(requestedLimit, 1, 10);
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("query.cond", safeCondition);
        if (!safeIntervention.isBlank()) parameters.put("query.intr", safeIntervention);
        if (!safeStatus.isBlank()) parameters.put("filter.overallStatus", safeStatus);
        parameters.put("pageSize", String.valueOf(limit));
        parameters.put("format", "json");
        JsonNode response = getJson(uri(TRIALS_BASE, parameters));
        List<Map<String, Object>> studies = new ArrayList<>();
        response.path("studies").forEach(study -> studies.add(trialSummary(study)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("condition", safeCondition);
        result.put("intervention", safeIntervention);
        result.put("status", safeStatus);
        result.put("count", studies.size());
        result.put("studies", studies);
        result.put("source", "ClinicalTrials.gov");
        return result;
    }

    private List<Map<String, Object>> fetchPubMed(List<String> ids) {
        String xml = getText(ncbiUri("efetch.fcgi", Map.of(
                "db", "pubmed", "id", String.join(",", ids), "retmode", "xml")), true);
        return parsePubMedXml(xml);
    }

    static List<Map<String, Object>> parsePubMedXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            List<Map<String, Object>> articles = new ArrayList<>();
            NodeList nodes = document.getElementsByTagName("PubmedArticle");
            for (int i = 0; i < nodes.getLength(); i++) articles.add(article((Element) nodes.item(i)));
            return articles;
        } catch (Exception e) {
            throw new IllegalStateException("PubMed XML解析失败: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> article(Element root) {
        Map<String, Object> value = new LinkedHashMap<>();
        String pmid = firstText(root, "PMID");
        value.put("pmid", pmid);
        value.put("title", firstText(root, "ArticleTitle"));
        value.put("abstract", abstractText(root));
        Element journal = firstElement(root, "Journal");
        value.put("journal", journal == null ? "" : firstText(journal, "Title"));
        value.put("publication_date", publicationDate(root));
        value.put("doi", articleId(root, "doi"));
        value.put("pmcid", articleId(root, "pmc"));
        value.put("publication_types", texts(root, "PublicationType"));
        value.put("url", "https://pubmed.ncbi.nlm.nih.gov/" + pmid + "/");
        value.put("source", "PubMed/NCBI");
        return value;
    }

    private static String abstractText(Element root) {
        List<String> parts = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName("AbstractText");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String label = element.getAttribute("Label");
            String text = normalize(element.getTextContent());
            if (!text.isBlank()) parts.add(label.isBlank() ? text : label + ": " + text);
        }
        return String.join("\n", parts);
    }

    private static String publicationDate(Element root) {
        Element date = firstElement(root, "PubDate");
        if (date == null) return "";
        String year = firstText(date, "Year");
        String month = firstText(date, "Month");
        String day = firstText(date, "Day");
        if (year.isBlank()) return firstText(date, "MedlineDate");
        return String.join("-", java.util.stream.Stream.of(year, month, day)
                .filter(value -> !value.isBlank()).toList());
    }

    private static String articleId(Element root, String type) {
        NodeList ids = root.getElementsByTagName("ArticleId");
        for (int i = 0; i < ids.getLength(); i++) {
            Element element = (Element) ids.item(i);
            if (type.equalsIgnoreCase(element.getAttribute("IdType"))) return normalize(element.getTextContent());
        }
        return "";
    }

    private static Map<String, Object> trialSummary(JsonNode study) {
        JsonNode protocol = study.path("protocolSection");
        JsonNode identification = protocol.path("identificationModule");
        JsonNode status = protocol.path("statusModule");
        JsonNode design = protocol.path("designModule");
        JsonNode conditions = protocol.path("conditionsModule");
        JsonNode interventions = protocol.path("armsInterventionsModule").path("interventions");
        String nctId = identification.path("nctId").asText("");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("nct_id", nctId);
        value.put("title", identification.path("briefTitle").asText(""));
        value.put("overall_status", status.path("overallStatus").asText(""));
        value.put("phases", strings(design.path("phases")));
        value.put("conditions", strings(conditions.path("conditions")));
        List<String> names = new ArrayList<>();
        interventions.forEach(item -> names.add(item.path("name").asText("")));
        value.put("interventions", names.stream().filter(item -> !item.isBlank()).toList());
        value.put("study_type", design.path("studyType").asText(""));
        value.put("url", "https://clinicaltrials.gov/study/" + nctId);
        return value;
    }

    private JsonNode getJson(URI uri) {
        String body = getText(uri, uri.toString().startsWith(NCBI_BASE));
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("医疗证据响应解析失败: " + e.getMessage(), e);
        }
    }

    private String getText(URI uri, boolean ncbi) {
        try {
            if (ncbi) throttleNcbi();
            // PubMed 搜索需要连续调用 esearch 与 efetch；单次请求限制为 12 秒，
            // 确保完整工具调用仍受 Tool Gateway 默认 30 秒总超时约束。
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET()
                    .header("Accept", "application/json, application/xml, text/xml")
                    .header("User-Agent", "Tian-AI-Agent/1.0" + (ncbiEmail.isBlank() ? "" : " (" + ncbiEmail + ")"));
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("官方医疗数据接口 HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("医疗证据查询被中断", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("医疗证据查询失败: " + e.getMessage(), e);
        }
    }

    private void throttleNcbi() throws InterruptedException {
        long interval = ncbiApiKey.isBlank() ? 350_000_000L : 110_000_000L;
        synchronized (rateLock) {
            long wait = interval - (System.nanoTime() - lastNcbiRequestNanos);
            if (wait > 0) Thread.sleep(Duration.ofNanos(wait));
            lastNcbiRequestNanos = System.nanoTime();
        }
    }

    private URI ncbiUri(String endpoint, Map<String, String> parameters) {
        Map<String, String> values = new LinkedHashMap<>(parameters);
        values.put("tool", "tian_ai_agent");
        if (!ncbiEmail.isBlank()) values.put("email", ncbiEmail);
        if (!ncbiApiKey.isBlank()) values.put("api_key", ncbiApiKey);
        return uri(NCBI_BASE + endpoint, values);
    }

    private static URI uri(String base, Map<String, String> parameters) {
        String query = parameters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        return URI.create(base + "?" + query);
    }

    private static String validPmid(String value) {
        String pmid = required(value, "pmid", 12);
        if (!pmid.matches("[1-9][0-9]{0,11}")) throw new IllegalArgumentException("pmid必须是数字");
        return pmid;
    }

    private static String required(String value, String name, int maxLength) {
        String safe = optional(value, maxLength);
        if (safe.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        return safe;
    }

    private static String optional(String value, int maxLength) {
        String safe = value == null ? "" : value.trim();
        if (safe.length() > maxLength) throw new IllegalArgumentException("参数长度不能超过" + maxLength);
        return safe;
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Element firstElement(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String firstText(Element root, String tag) {
        Element element = firstElement(root, tag);
        return element == null ? "" : normalize(element.getTextContent());
    }

    private static List<String> texts(Element root, String tag) {
        List<String> result = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            String value = normalize(node.getTextContent());
            if (!value.isBlank()) result.add(value);
        }
        return result;
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static double titleSimilarity(String expected, String actual) {
        if (expected == null || expected.isBlank()) return 1.0;
        Set<String> left = tokens(expected);
        Set<String> right = tokens(actual);
        if (left.isEmpty() || right.isEmpty()) return 0;
        long intersection = left.stream().filter(right::contains).count();
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return (double) intersection / union.size();
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isBlank()) result.add(token);
        }
        return result;
    }
}
