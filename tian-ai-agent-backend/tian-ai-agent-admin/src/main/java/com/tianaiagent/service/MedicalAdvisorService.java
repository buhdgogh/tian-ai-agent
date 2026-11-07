package com.tianaiagent.service;

import com.tianaiagent.advisor.MyLoggerAdvisor;
import com.tianaiagent.chatmemory.EnterpriseMemoryService;
import com.tianaiagent.chatmemory.FileBasedChatMemory;
import com.tianaiagent.chatmemory.LayeredMemoryService;
import com.tianaiagent.rag.QueryRewriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/** 面向健康教育和循证信息查询的医疗助手，不提供诊断或处方。 */
@Service
@Slf4j
public class MedicalAdvisorService {
    private static final int RAG_REWRITE_HISTORY_MESSAGES = 12;
    private static final int RAG_REWRITE_HISTORY_CHARS = 6000;
    static final String SYSTEM_PROMPT = """
            你是“天医”，一个医疗信息与安全支持助手，仅用于健康教育、资料解释和就医决策支持。
            你不能替代医生、药师或急救服务，不得给出确定诊断、处方、停药换药或个体化剂量指令。

            回答规则：
            1. 优先识别急症红旗；胸痛、呼吸困难、意识障碍、卒中征象、严重过敏、大量出血、药物过量或自伤风险应立即建议拨打120或就近急诊。
            2. 先区分健康教育问题与患者特异问题。个体问题缺少年龄、症状时间、既往病史、过敏史、合并用药、妊娠哺乳或肝肾功能时，只询问必要信息。
            3. 医疗事实必须可追溯到提供的证据。注明发布机构、指南/标签版本和日期；证据不足时明确拒绝下结论。
            4. 来源存在冲突时分别陈述适用人群、版本和差异，不擅自消除冲突。
            5. 药物回答按适应症、禁忌、常见与严重不良反应、相互作用、特殊人群和监测要点组织；不得指导用户自行改变处方。
            6. 使用“已知信息—证据解释—不确定性—红旗信号—建议下一步”的结构，明确哪些内容需要医生或药师审核。
            7. 不虚构患者资料、检查值、指南结论、药品监管状态或引用。
            8. 最小化收集健康信息，提醒用户不要提交姓名、身份证号、完整住址、病历号等不必要的可识别信息。
            """;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatMemory ragChatMemory;
    private final EnterpriseMemoryService memoryService;
    private final MedicalSafetyService safetyService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;
    private final QueryRewriter queryRewriter;
    private final LayeredMemoryService layeredMemory;

    public MedicalAdvisorService(ChatModel routedChatModel,
                                 EnterpriseMemoryService memoryService,
                                 MedicalSafetyService safetyService,
                                 KnowledgeBaseService knowledgeBaseService,
                                 ObjectMapper objectMapper,
                                 QueryRewriter queryRewriter,
                                 LayeredMemoryService layeredMemory) {
        this.memoryService = memoryService;
        this.safetyService = safetyService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
        this.queryRewriter = queryRewriter;
        this.layeredMemory = layeredMemory;
        this.chatMemory = memoryService.chatMemory(EnterpriseMemoryService.MEDICAL_ADVISOR);
        this.ragChatMemory = memoryService.chatMemory(EnterpriseMemoryService.MEDICAL_RAG);
        this.chatClient = ChatClient.builder(routedChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), new MyLoggerAdvisor())
                .build();
    }

    public List<FileBasedChatMemory.ConversationSummary> listConversations() {
        return memoryService.listConversations(EnterpriseMemoryService.MEDICAL_ADVISOR);
    }
    public void clearChat(String chatId) {
        layeredMemory.deleteConversation(EnterpriseMemoryService.MEDICAL_ADVISOR, chatId, chatMemory);
    }
    public List<Message> getChatMessages(String chatId) { return chatMemory.get(chatId); }
    public List<FileBasedChatMemory.ConversationSummary> listRagConversations() {
        return memoryService.listConversations(EnterpriseMemoryService.MEDICAL_RAG);
    }
    public void clearRagChat(String chatId) {
        layeredMemory.deleteConversation(EnterpriseMemoryService.MEDICAL_RAG, chatId, ragChatMemory);
    }
    public List<Message> getRagChatMessages(String chatId) { return ragChatMemory.get(chatId); }

    public String doChat(String message, String chatId) {
        MedicalSafetyService.RiskAssessment risk = safetyService.assess(message);
        if (risk.emergency()) return risk.message();
        String result = chatClient.prompt().system(SYSTEM_PROMPT + layeredMemory.context(
                        EnterpriseMemoryService.MEDICAL_ADVISOR, chatId, message)).user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call().content();
        layeredMemory.scheduleCompaction(EnterpriseMemoryService.MEDICAL_ADVISOR, chatId, chatMemory);
        return result;
    }

    public Flux<String> doChatByStream(String message, String chatId) {
        MedicalSafetyService.RiskAssessment risk = safetyService.assess(message);
        if (risk.emergency()) return Flux.just(risk.message());
        return chatClient.prompt().system(SYSTEM_PROMPT + layeredMemory.context(
                        EnterpriseMemoryService.MEDICAL_ADVISOR, chatId, message)).user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream().content()
                .doOnComplete(() -> layeredMemory.scheduleCompaction(
                        EnterpriseMemoryService.MEDICAL_ADVISOR, chatId, chatMemory));
    }

    public MedicalEvidenceReport createEvidenceReport(String message, String chatId) {
        MedicalSafetyService.RiskAssessment risk = safetyService.assess(message);
        if (risk.emergency()) {
            return new MedicalEvidenceReport("紧急风险提示", risk.level(), risk.message(), risk.signals(),
                    List.of(), List.of("尚未进行医学评估"), List.of("立即联系120或就近急诊"), true);
        }
        return chatClient.prompt()
                .system(SYSTEM_PROMPT + """

                        生成结构化医疗证据报告。不得输出确定诊断或处方。每个事实结论必须在 evidence 中描述来源；
                        没有可靠证据的内容放入 uncertainties。药物、特殊人群或高风险结论必须将 requiresProfessionalReview 设为 true。
                        """)
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call().entity(MedicalEvidenceReport.class);
    }

    @Resource private ToolCallback[] allTools;

    public JsonNode doChatWithRag(String message, String chatId) {
        MedicalSafetyService.RiskAssessment risk = safetyService.assess(message);
        if (risk.emergency()) {
            var result = objectMapper.createObjectNode();
            result.put("question", message);
            result.put("answer", risk.message());
            result.put("riskLevel", risk.level());
            result.putArray("sources");
            rememberRagTurn(chatId, message, risk.message());
            return result;
        }

        List<Message> history = ragChatMemory.get(chatId);
        String longTermContext = layeredMemory.context(EnterpriseMemoryService.MEDICAL_RAG, chatId, message);
        String retrievalQuestion = rewriteRagQuestion(message, chatId, history, longTermContext);
        JsonNode queryResult = knowledgeBaseService.queryWithSources(retrievalQuestion, 8);
        String answer = queryResult.path("answer").asText("");
        rememberRagTurn(chatId, message, answer);
        layeredMemory.scheduleCompaction(EnterpriseMemoryService.MEDICAL_RAG, chatId, ragChatMemory);

        if (queryResult instanceof ObjectNode objectResult) {
            objectResult.put("originalQuestion", message);
            objectResult.put("retrievalQuestion", retrievalQuestion);
        }
        return queryResult;
    }

    private String rewriteRagQuestion(String message, String chatId, List<Message> history,
                                      String longTermContext) {
        if ((history == null || history.isEmpty()) && (longTermContext == null || longTermContext.isBlank())) {
            return message;
        }
        List<Message> safeHistory = history == null ? List.of() : history;
        int start = Math.max(0, safeHistory.size() - RAG_REWRITE_HISTORY_MESSAGES);
        StringBuilder context = new StringBuilder();
        for (int i = start; i < safeHistory.size(); i++) {
            Message previous = safeHistory.get(i);
            String role = previous instanceof UserMessage ? "用户"
                    : previous instanceof AssistantMessage ? "助手" : null;
            String text = previous.getText();
            if (role == null || text == null || text.isBlank()) continue;
            if (context.length() + text.length() > RAG_REWRITE_HISTORY_CHARS) break;
            context.append(role).append("：").append(text).append('\n');
        }
        if (context.isEmpty() && (longTermContext == null || longTermContext.isBlank())) return message;
        String prompt = """
                根据历史对话，将当前问题改写为一个语义完整、不依赖上下文、适合知识库检索的问题。
                必须保持用户原意，不要回答问题；如果当前问题本身完整，原样返回。

                历史对话：
                %s
                长期会话摘要（仅用于消除指代，不作为医学证据）：
                %s
                当前问题：%s
                """.formatted(context, longTermContext == null ? "" : longTermContext, message);
        try {
            String rewritten = queryRewriter.doQueryRewrite(prompt);
            return rewritten == null || rewritten.isBlank() ? message : rewritten.trim();
        } catch (RuntimeException e) {
            log.warn("RAG 上下文查询重写失败，回退为原始问题: chatId={}", chatId, e);
            return message;
        }
    }

    private void rememberRagTurn(String chatId, String question, String answer) {
        ragChatMemory.add(chatId, List.of(
                new UserMessage(question),
                new AssistantMessage(answer == null ? "" : answer)));
    }

    public String doChatWithTools(String message, String chatId) {
        MedicalSafetyService.RiskAssessment risk = safetyService.assess(message);
        if (risk.emergency()) return risk.message();
        return chatClient.prompt().user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolCallbacks(allTools)
                .toolContext(java.util.Map.of(com.tianaiagent.wiki.WikiTraversalState.CONTEXT_KEY,
                        executionWikiState()))
                .call().content();
    }

    private com.tianaiagent.wiki.WikiTraversalState executionWikiState() {
        var state = com.tianaiagent.observability.ExecutionContext.current().wikiTraversal();
        return state == null ? new com.tianaiagent.wiki.WikiTraversalState() : state;
    }

    public record MedicalEvidenceReport(String title, String riskLevel, String summary,
                                        List<String> redFlags, List<String> evidence,
                                        List<String> uncertainties, List<String> nextSteps,
                                        boolean requiresProfessionalReview) {}
}
