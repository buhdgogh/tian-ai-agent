package com.tianaiagent.trigger.http;

import com.tianaiagent.api.agent.ModelProviderSettingsRequest;
import com.tianaiagent.domain.agent.model.valobj.ModelProvider;
import com.tianaiagent.domain.agent.service.ModelProviderSettingsService;
import com.tianaiagent.trigger.security.RequestIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/model-providers")
class ModelProviderSettingsController {
    private final ModelProviderSettingsService settings;

    ModelProviderSettingsController(ModelProviderSettingsService settings) { this.settings = settings; }

    @GetMapping
    ModelProviderSettingsService.Settings list(@RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return settings.list(identity.ownerId());
    }

    @PutMapping
    ModelProviderSettingsService.Settings save(@RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity,
                                               @RequestBody ModelProviderSettingsRequest request) {
        if (request.providers() == null || request.providers().size() > 20) throw new IllegalArgumentException("最多配置 20 个模型");
        return settings.save(identity.ownerId(), request.version(), request.providers().stream().map(this::convert).toList());
    }

    @ExceptionHandler(ModelProviderSettingsService.OriginNotAllowed.class)
    ResponseEntity<Map<String, String>> originNotAllowed() {
        return ResponseEntity.badRequest().body(Map.of("message", "此 Base URL 的来源未加入后端模型地址白名单。"));
    }

    @ExceptionHandler(ModelProviderSettingsService.Conflict.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "模型配置已更新，请重新加载后保存。"));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("message",
                "请检查提供商 ID、接口协议、模型名称、Base URL 和 API Key。修改协议或地址时需要重新输入 API Key。"));
    }

    private ModelProvider convert(ModelProviderSettingsRequest.Provider provider) {
        if (provider == null || provider.protocol() == null) throw new IllegalArgumentException("请选择协议");
        return new ModelProvider(clean(provider.id()),
                ModelProvider.Protocol.valueOf(provider.protocol().strip().toUpperCase(Locale.ROOT)),
                clean(provider.baseUrl()), clean(provider.apiKey()), clean(provider.model()),
                provider.priority() == null ? 0 : provider.priority(),
                !Boolean.FALSE.equals(provider.enabled()), clean(provider.workspaceId()),
                !Boolean.FALSE.equals(provider.streamUsage()), clean(provider.reasoningEffort()));
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
