package com.tianaiagent.trigger.http;

import com.tianaiagent.api.agent.AgentPresetRequest;
import com.tianaiagent.domain.agent.model.valobj.AgentPreset;
import com.tianaiagent.domain.agent.model.valobj.AgentPreset.Kind;
import com.tianaiagent.domain.agent.service.AgentPresetService;
import com.tianaiagent.trigger.security.RequestIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/{collection:skills|prompt-templates}")
class AgentPresetController {
    private final AgentPresetService presets;

    AgentPresetController(AgentPresetService presets) { this.presets = presets; }

    @GetMapping
    List<AgentPreset> list(@PathVariable String collection, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return presets.list(identity.ownerId(), kind(collection));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AgentPreset create(@PathVariable String collection, @RequestBody AgentPresetRequest request,
                       @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return presets.create(identity.ownerId(), kind(collection), request.name(), request.description(),
                request.content(), !Boolean.FALSE.equals(request.enabled()));
    }

    @PutMapping("/{id}")
    AgentPreset update(@PathVariable String collection, @PathVariable String id, @RequestBody AgentPresetRequest request,
                       @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        if (request.enabled() == null) throw new IllegalArgumentException("请指定启用状态");
        return presets.update(identity.ownerId(), kind(collection), id, request.name(), request.description(),
                request.content(), request.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String collection, @PathVariable String id,
                @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        presets.delete(identity.ownerId(), kind(collection), id);
    }

    @ExceptionHandler(AgentPresetService.PresetNotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void notFound() {}

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    private static Kind kind(String collection) {
        return "skills".equals(collection) ? Kind.SKILL : Kind.PROMPT_TEMPLATE;
    }
}
