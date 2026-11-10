package com.tianaiagent.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionUtils;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.tianaiagent.domain.agent.adapter.repository.ModelProviderStore;
import com.tianaiagent.domain.agent.model.valobj.ModelProvider;
import com.tianaiagent.domain.agent.service.ModelProviderSettingsService;
import com.tianaiagent.infrastructure.gateway.model.RoutedChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentModelProperties.class, DashScopeChatProperties.class,
        DashScopeConnectionProperties.class, DashScopeEmbeddingProperties.class})
class AgentModelConfiguration {
    private static final String BEIJING_WORKSPACE_EMBEDDING_ORIGIN = "https://*.cn-beijing.maas.aliyuncs.com";

    @Bean(name = {"routedChatModel", "dashscopeChatModel"})
    @Primary
    RoutedChatModel routedChatModel(AgentModelProperties config, ModelTargets targets) {
        return new RoutedChatModel(targets.defaults(), config.timeout(), config.cooldown());
    }

    @Bean
    ModelProviderSettingsService modelProviderSettingsService(ModelProviderStore store, ModelTargets targets,
                                                               RoutedChatModel router, AgentModelProperties config) {
        Set<Origin> origins = new HashSet<>();
        boolean allowBeijingWorkspaceEmbeddings = config.allowedUserOrigins().contains(BEIJING_WORKSPACE_EMBEDDING_ORIGIN);
        for (String url : config.allowedUserOrigins()) {
            if (BEIJING_WORKSPACE_EMBEDDING_ORIGIN.equals(url)) continue;
            URI uri = URI.create(url);
            if (uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme())
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().matches("/?")) {
                throw new IllegalArgumentException("agent.models.allowed-user-origins 必须填写 HTTP 或 HTTPS 来源");
            }
            origins.add(Origin.from(uri));
        }
        Predicate<ModelProvider> allowed = provider -> origins.contains(Origin.from(URI.create(provider.baseUrl())))
                || (allowBeijingWorkspaceEmbeddings && provider.usesBeijingWorkspaceEmbedding());
        return new ModelProviderSettingsService(store, allowed, (owner, providers) -> {
            var chatProviders = providers.stream()
                    .filter(provider -> provider.protocol() != ModelProvider.Protocol.OPENAI_EMBEDDING).toList();
            var permitted = chatProviders.stream().filter(ModelProvider::enabled).filter(allowed).toList();
            return router.prepareTargets(owner, targets.build().apply(permitted), !chatProviders.isEmpty());
        });
    }

    private record Origin(String scheme, String host, int port) {
        static Origin from(URI uri) {
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
            return new Origin(scheme, uri.getHost().toLowerCase(Locale.ROOT), port);
        }
    }

    @Bean
    ModelTargets modelTargets(AgentModelProperties config, DashScopeConnectionProperties legacyConnection,
                              DashScopeChatProperties legacyChat, ObjectProvider<ToolCallingManager> managers) {
        ToolCallingManager toolManager = managers.getIfAvailable(() -> ToolCallingManager.builder().build());
        HttpClient http = HttpClient.newBuilder().connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(config.timeout());
        RestClient.Builder rest = RestClient.builder().requestFactory(requestFactory);
        WebClient.Builder web = WebClient.builder().clientConnector(new JdkClientHttpConnector(http));
        var retry = RetryTemplate.builder().maxAttempts(1).build();

        Function<List<ModelProvider>, List<RoutedChatModel.Target>> build = providers -> {
            List<RoutedChatModel.Target> targets = new ArrayList<>();
            for (ModelProvider provider : providers) {
                if (!provider.enabled()) continue;
                var model = switch (provider.protocol()) {
                    case DASHSCOPE -> {
                        var api = DashScopeApi.builder().apiKey(provider.apiKey()).baseUrl(provider.baseUrl())
                                .workSpaceId(provider.workspaceId()).restClientBuilder(rest.clone())
                                .webClientBuilder(web.clone()).responseErrorHandler(new DefaultResponseErrorHandler()).build();
                        yield DashScopeChatModel.builder().dashScopeApi(api)
                                .defaultOptions(DashScopeChatOptions.builder().withModel(provider.model()).build())
                                .toolCallingManager(toolManager).retryTemplate(retry).build();
                    }
                    case OPENAI_COMPATIBLE -> {
                        var api = OpenAiApi.builder().apiKey(provider.apiKey()).baseUrl(provider.baseUrl())
                                .completionsPath("/chat/completions").restClientBuilder(rest.clone())
                                .webClientBuilder(web.clone()).responseErrorHandler(new DefaultResponseErrorHandler()).build();
                        yield OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder()
                                        .model(provider.model()).streamUsage(provider.streamUsage())
                                        .reasoningEffort(provider.reasoningEffort()).build())
                                .toolCallingManager(toolManager).retryTemplate(retry).build();
                    }
                    case ANTHROPIC -> {
                        String baseUrl = provider.baseUrl().replaceFirst("/+$", "");
                        // 保留 HTTP 状态，让模型路由能够识别流式限流和服务故障。
                        var anthropicWeb = web.clone().filter((request, next) -> next.exchange(request)
                                .flatMap(response -> response.statusCode().isError()
                                        ? response.createError() : Mono.just(response)));
                        var api = AnthropicApi.builder().apiKey(provider.apiKey()).baseUrl(baseUrl)
                                .completionsPath(baseUrl.endsWith("/v1") ? "/messages" : "/v1/messages")
                                .restClientBuilder(rest.clone()).webClientBuilder(anthropicWeb)
                                .responseErrorHandler(new DefaultResponseErrorHandler()).build();
                        yield AnthropicChatModel.builder().anthropicApi(api)
                                .defaultOptions(AnthropicChatOptions.builder().model(provider.model()).maxTokens(4096).build())
                                .toolCallingManager(toolManager).retryTemplate(retry).build();
                    }
                    case OPENAI_EMBEDDING -> throw new IllegalArgumentException("嵌入模型不能用于聊天");
                };
                targets.add(new RoutedChatModel.Target(provider.id(), provider.model(), provider.priority(), model));
            }
            return List.copyOf(targets);
        };

        List<ModelProvider> providers = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < config.providers().size(); index++) {
            var provider = config.providers().get(index);
            var settings = new ModelProvider(provider.id(), provider.protocol(),
                    provider.baseUrl(), provider.apiKey(), provider.model(),
                    provider.priority() == null ? index : provider.priority(), true, provider.workspaceId(),
                    provider.streamUsage(), provider.reasoningEffort());
            settings.validate();
            if (!ids.add(settings.id())) throw new IllegalArgumentException("提供商 ID 必须唯一");
            providers.add(settings);
        }

        List<RoutedChatModel.Target> defaults = new ArrayList<>(build.apply(providers));
        if (providers.isEmpty()) {
            if (StringUtils.hasText(legacyChat.getApiKey()) || StringUtils.hasText(legacyConnection.getApiKey())) {
                var legacy = DashScopeConnectionUtils.resolveConnectionProperties(legacyConnection, legacyChat, "chat");
                var api = DashScopeApi.builder().apiKey(legacy.apiKey()).baseUrl(legacy.baseUrl())
                        .workSpaceId(legacy.workspaceId()).headers(legacy.headers()).restClientBuilder(rest.clone())
                        .webClientBuilder(web.clone()).responseErrorHandler(new DefaultResponseErrorHandler()).build();
                var model = DashScopeChatModel.builder().dashScopeApi(api).defaultOptions(legacyChat.getOptions())
                        .toolCallingManager(toolManager).retryTemplate(retry).build();
                defaults.add(new RoutedChatModel.Target("dashscope", legacyChat.getOptions().getModel(), 0, model));
            }
        }
        return new ModelTargets(List.copyOf(defaults), build);
    }

    record ModelTargets(List<RoutedChatModel.Target> defaults,
                        Function<List<ModelProvider>, List<RoutedChatModel.Target>> build) {}

    @Bean
    @Conditional(LegacyEmbeddingConfigured.class)
    EmbeddingModel dashscopeEmbeddingModel(DashScopeConnectionProperties connection, DashScopeEmbeddingProperties embedding,
                                            ObjectProvider<RestClient.Builder> rest, ObjectProvider<WebClient.Builder> web,
                                            ObjectProvider<RetryTemplate> retry, ObjectProvider<ResponseErrorHandler> errors,
                                            ObjectProvider<ObservationRegistry> observations,
                                            ObjectProvider<EmbeddingModelObservationConvention> conventions) {
        return new DashScopeEmbeddingAutoConfiguration().dashscopeEmbeddingModel(connection, embedding,
                rest.getIfAvailable(RestClient::builder), web.getIfAvailable(WebClient::builder),
                retry.getIfAvailable(() -> RetryTemplate.builder().maxAttempts(1).build()),
                errors.getIfAvailable(DefaultResponseErrorHandler::new), observations, conventions);
    }

    static final class LegacyEmbeddingConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            return !"none".equalsIgnoreCase(env.getProperty("spring.ai.model.embedding"))
                    && (StringUtils.hasText(env.getProperty("spring.ai.dashscope.embedding.api-key"))
                    || StringUtils.hasText(env.getProperty("spring.ai.dashscope.api-key")));
        }
    }

}
