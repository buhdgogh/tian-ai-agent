package com.tianaiagent.rag.enterprise;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.tianaiagent.rag.enterprise.HybridRetrievalService.Evidence;

@Service
public class CitedAnswerService {
    private final HybridRetrievalService retrievalService;
    private final ChatClient chatClient;
    private final double minimumRelevantScore;
    private final double minimumSufficientScore;

    public CitedAnswerService(HybridRetrievalService retrievalService, ChatModel routedChatModel,
                              @Value("${agent.rag.evidence.minimum-relevant-score:0.15}") double minimumRelevantScore,
                              @Value("${agent.rag.evidence.minimum-sufficient-score:0.35}") double minimumSufficientScore) {
        this.retrievalService = retrievalService;
        this.chatClient = ChatClient.builder(routedChatModel).build();
        this.minimumRelevantScore = minimumRelevantScore;
        this.minimumSufficientScore = minimumSufficientScore;
    }

    public CitedAnswer answer(String question, int topK) {
        List<Evidence> evidence = retrievalService.search(question, topK);
        if (evidence.isEmpty() || evidence.getFirst().score() < minimumRelevantScore) {
            return new CitedAnswer("知识库中没有相关证据，无法回答该问题。", List.of(),
                    "NO_RELEVANT_EVIDENCE", true);
        }
        if (evidence.getFirst().score() < minimumSufficientScore) {
            return new CitedAnswer("找到了相关资料，但证据不足以支持可靠结论。", references(evidence),
                    "INSUFFICIENT_EVIDENCE", true);
        }
        StringBuilder context = new StringBuilder();
        for (Evidence item : evidence) {
            context.append('[').append(item.citation()).append("] 文件：")
                    .append(item.fileName()).append("；章节：").append(item.section()).append('\n')
                    .append(item.text()).append("\n\n");
        }
        String answer = chatClient.prompt()
                .system("你是严格基于证据回答问题的知识库助手。只能使用给定证据；每个事实后使用[数字]标注来源。证据不足时明确说明，不得编造引用。")
                .user("问题：" + question + "\n\n证据：\n" + context)
                .call().content();
        return new CitedAnswer(answer, references(evidence), "SUFFICIENT_EVIDENCE", false);
    }

    private List<SourceReference> references(List<Evidence> evidence) {
        return evidence.stream().map(item -> new SourceReference(
                item.citation(), item.documentId(), item.fileName(), item.chunkId(), item.section(),
                item.text(), item.score())).toList();
    }

    public record CitedAnswer(String answer, List<SourceReference> sources,
                              String evidenceLevel, boolean requiresHumanReview) {}
    public record SourceReference(int citation, String documentId, String fileName, String chunkId,
                                  String section, String quote, double score) {}
}
