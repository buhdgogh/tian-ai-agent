package com.tianaiagent.domain.knowledge.service;

import com.tianaiagent.domain.agent.model.valobj.ModelProvider;
import com.tianaiagent.domain.agent.service.ModelProviderSettingsService;
import com.tianaiagent.domain.knowledge.adapter.port.TextEmbedding;
import com.tianaiagent.domain.knowledge.model.entity.KnowledgeSpace;
import com.tianaiagent.domain.knowledge.model.valobj.EmbeddingBinding;

import java.util.List;
import java.util.function.Function;

public class SpaceEmbeddings {
    private final TextEmbedding defaults;
    private final ModelProviderSettingsService settings;
    private final Function<ModelProvider, TextEmbedding> client;

    public SpaceEmbeddings(TextEmbedding defaults, ModelProviderSettingsService settings,
                           Function<ModelProvider, TextEmbedding> client) {
        this.defaults = defaults;
        this.settings = settings;
        this.client = client;
    }

    public EmbeddingBinding bind(String ownerId, String providerId) {
        if (providerId == null || providerId.isBlank()) return null;
        ModelProvider provider = provider(ownerId, providerId);
        int dimensions;
        try {
            dimensions = client.apply(provider).embedQuery("embedding configuration check").length;
        } catch (IllegalStateException failure) {
            throw new Unavailable("无法验证嵌入模型：" + failure.getMessage());
        }
        return new EmbeddingBinding(ownerId, providerId, baseUrl(provider), provider.model(), dimensions);
    }

    public List<float[]> embedDocuments(KnowledgeSpace space, List<String> texts) {
        List<float[]> vectors;
        try {
            vectors = resolve(space).embedDocuments(texts);
        } catch (IllegalStateException failure) {
            if (space.embedding() == null) throw failure;
            throw new Unavailable(failure.getMessage());
        }
        vectors.forEach(vector -> verifyDimensions(space, vector));
        return vectors;
    }

    public float[] embedQuery(KnowledgeSpace space, String text) {
        float[] vector;
        try {
            vector = resolve(space).embedQuery(text);
        } catch (IllegalStateException failure) {
            if (space.embedding() == null) throw failure;
            throw new Unavailable(failure.getMessage());
        }
        verifyDimensions(space, vector);
        return vector;
    }

    private TextEmbedding resolve(KnowledgeSpace space) {
        EmbeddingBinding binding = space.embedding();
        if (binding == null) return defaults;
        ModelProvider provider = provider(binding.ownerId(), binding.providerId());
        if (!baseUrl(provider).equals(binding.baseUrl()) || !provider.model().equals(binding.model())) {
            throw new Unavailable("该知识库绑定的嵌入模型已更改，请恢复原模型和 Base URL");
        }
        return client.apply(provider);
    }

    private ModelProvider provider(String ownerId, String providerId) {
        return settings.embeddingProvider(ownerId, providerId).orElseThrow(
                () -> new Unavailable("所选嵌入模型不可用，请检查模型是否已启用以及 Base URL 是否受信任"));
    }

    private static String baseUrl(ModelProvider provider) {
        return provider.baseUrl().replaceFirst("/+$", "");
    }

    private static void verifyDimensions(KnowledgeSpace space, float[] vector) {
        if (space.embedding() != null && vector.length != space.embedding().dimensions()) {
            throw new Unavailable("嵌入接口返回的向量维度与知识库绑定的维度不一致");
        }
    }

    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
    }
}
