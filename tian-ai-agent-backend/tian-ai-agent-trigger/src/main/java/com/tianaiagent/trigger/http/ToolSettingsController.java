package com.tianaiagent.trigger.http;

import com.tianaiagent.api.agent.ToolCredentialRequest;
import com.tianaiagent.domain.agent.model.valobj.PersonalTool;
import com.tianaiagent.domain.agent.service.ToolCredentialService;
import com.tianaiagent.trigger.security.RequestIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/tool-settings")
public class ToolSettingsController {
    private final ToolCredentialService credentials;

    public ToolSettingsController(ToolCredentialService credentials) { this.credentials = credentials; }

    @GetMapping
    public Map<String, ToolCredentialService.Source> list(@RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        return Map.of("mineru", credentials.source(identity.ownerId(), PersonalTool.MINERU),
                "search", credentials.source(identity.ownerId(), PersonalTool.SEARCH));
    }

    @PutMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable String name, @RequestBody ToolCredentialRequest request,
                     @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        try {
            credentials.save(identity.ownerId(), tool(name), request.value());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String name, @RequestAttribute(RequestIdentity.ATTRIBUTE) RequestIdentity identity) {
        credentials.clear(identity.ownerId(), tool(name));
    }

    private static PersonalTool tool(String name) {
        return switch (name) {
            case "mineru" -> PersonalTool.MINERU;
            case "search" -> PersonalTool.SEARCH;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
    }
}
