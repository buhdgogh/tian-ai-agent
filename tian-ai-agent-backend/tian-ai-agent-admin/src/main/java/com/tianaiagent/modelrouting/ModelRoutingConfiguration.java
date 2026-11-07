package com.tianaiagent.modelrouting;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelRoutingProperties.class)
public class ModelRoutingConfiguration {
    @Bean
    @Primary
    ChatModel routedChatModel(ModelRoutingProperties properties,
                              @Qualifier("dashscopeChatModel") ObjectProvider<ChatModel> legacyModel,
                              ToolCallingManager toolManager,
                              ObjectProvider<ObservationRegistry> observations) {
        if (properties.providers() == null || properties.providers().isEmpty()) {
            return legacyModel.getObject();
        }
        Assert.isTrue(positive(properties.connectTimeout()) && positive(properties.requestTimeout())
                        && properties.cooldown() != null && !properties.cooldown().isNegative(),
                "Model routing requires positive timeouts and a non-negative cooldown");
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.requestTimeout());
        RestClient.Builder rest = RestClient.builder().requestFactory(requestFactory);
        WebClient.Builder web = WebClient.builder().clientConnector(new JdkClientHttpConnector(http));
        ObservationRegistry registry = observations.getIfUnique(() -> ObservationRegistry.NOOP);
        Set<String> ids = new HashSet<>();
        var routes = properties.providers().stream().filter(ModelRoutingProperties.Provider::enabled).map(provider -> {
            validate(provider, ids);
            return new RoutingChatModel.Route(provider.id(), provider.priority(), provider.supportsTools(),
                    provider.supportsStreaming(), createModel(provider, rest.clone(), web.clone(), toolManager, registry));
        }).toList();
        Assert.notEmpty(routes, "Model routing requires at least one enabled provider");
        return new RoutingChatModel(routes, properties.requestTimeout(), properties.cooldown(), toolManager);
    }

    private ChatModel createModel(ModelRoutingProperties.Provider provider, RestClient.Builder rest,
                                  WebClient.Builder web, ToolCallingManager tools, ObservationRegistry registry) {
        // Routing owns retries. Keep provider HTTP status codes for classification.
        RetryTemplate retry = RetryTemplate.builder().maxAttempts(1).build();
        var errors = new DefaultResponseErrorHandler();
        return switch (provider.protocol()) {
            case DASHSCOPE -> DashScopeChatModel.builder()
                    .dashScopeApi(DashScopeApi.builder().baseUrl(provider.baseUrl()).apiKey(provider.apiKey())
                            .restClientBuilder(rest).webClientBuilder(web).responseErrorHandler(errors).build())
                    .defaultOptions(DashScopeChatOptions.builder().withModel(provider.model()).build())
                    .retryTemplate(retry).toolCallingManager(tools).observationRegistry(registry).build();
            case OPENAI_COMPATIBLE -> OpenAiChatModel.builder()
                    .openAiApi(OpenAiApi.builder().baseUrl(provider.baseUrl()).apiKey(provider.apiKey())
                            .completionsPath(provider.completionsPath()).restClientBuilder(rest)
                            .webClientBuilder(web).responseErrorHandler(errors).build())
                    .defaultOptions(OpenAiChatOptions.builder().model(provider.model())
                            .reasoningEffort(provider.reasoningEffort()).build())
                    .retryTemplate(retry).toolCallingManager(tools).observationRegistry(registry).build();
        };
    }

    private void validate(ModelRoutingProperties.Provider provider, Set<String> ids) {
        Assert.isTrue(provider.id() != null && provider.id().matches("[a-zA-Z0-9_-]{1,64}") && ids.add(provider.id()),
                "Enabled model providers require unique ids (letters, numbers, underscores or hyphens)");
        String prefix = "Model provider " + provider.id();
        Assert.isTrue(provider.protocol() != null && StringUtils.hasText(provider.model()) && provider.priority() >= 0,
                prefix + " requires protocol, model and non-negative priority");
        Assert.isTrue(StringUtils.hasText(provider.apiKey()) && !provider.apiKey().startsWith("${"),
                prefix + " requires an API key");
        Assert.isTrue(validBaseUrl(provider.baseUrl()), prefix + " requires a valid HTTP(S) base URL");
        if (provider.protocol() == ModelRoutingProperties.Protocol.OPENAI_COMPATIBLE) {
            Assert.isTrue(provider.completionsPath() != null && provider.completionsPath().matches("/[a-zA-Z0-9_./-]+")
                            && !provider.completionsPath().contains("//"),
                    prefix + " requires a valid completions path");
        }
    }

    private boolean validBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
