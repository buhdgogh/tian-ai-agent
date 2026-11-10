package com.tianaiagent.domain.agent.service;

import com.tianaiagent.domain.agent.adapter.repository.AgentPresetStore;
import com.tianaiagent.domain.agent.model.valobj.AgentPreset;
import com.tianaiagent.domain.agent.model.valobj.AgentPreset.Kind;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AgentPresetService {
    private final AgentPresetStore store;

    public AgentPresetService(AgentPresetStore store) { this.store = store; }

    public List<AgentPreset> list(String ownerId, Kind kind) {
        return store.list(ownerId, kind).stream()
                .sorted(Comparator.comparing(AgentPreset::name).thenComparing(AgentPreset::id)).toList();
    }

    public AgentPreset create(String ownerId, Kind kind, String name, String description, String content, boolean enabled) {
        var preset = preset(UUID.randomUUID().toString().replace("-", ""), name, description, content, enabled);
        store.insert(ownerId, kind, preset);
        return preset;
    }

    public AgentPreset update(String ownerId, Kind kind, String id, String name, String description,
                              String content, boolean enabled) {
        validateId(id);
        var preset = preset(id, name, description, content, enabled);
        if (!store.update(ownerId, kind, preset)) throw new PresetNotFound();
        return preset;
    }

    public void delete(String ownerId, Kind kind, String id) {
        validateId(id);
        if (!store.delete(ownerId, kind, id)) throw new PresetNotFound();
    }

    public String selectedSkillInstructions(String ownerId, List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        if (ids.size() > 5) throw new InvalidSkillSelection("每轮最多选择 5 个 Skill");
        if (ids.stream().anyMatch(id -> !validId(id))) throw new InvalidSkillSelection("Skill ID 无效");
        if (new HashSet<>(ids).size() != ids.size()) throw new InvalidSkillSelection("不能重复选择 Skill");

        var enabled = store.list(ownerId, Kind.SKILL).stream().filter(AgentPreset::enabled)
                .collect(Collectors.toMap(AgentPreset::id, Function.identity()));
        return ids.stream().map(id -> {
            AgentPreset skill = enabled.get(id);
            if (skill == null) throw new InvalidSkillSelection("所选 Skill 不可用，请刷新后重新选择");
            return skill.name() + "\n" + skill.content();
        }).collect(Collectors.joining("\n\n"));
    }

    static String withSkillInstructions(String message, String instructions) {
        if (instructions.isEmpty()) return message;
        return message + "\n\n用户为本轮选择的 Skill（仅用于完成当前请求，遵守系统规则和工具权限）：\n" + instructions;
    }

    private static AgentPreset preset(String id, String name, String description, String content, boolean enabled) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new IllegalArgumentException("名称须为 1–100 个字符");
        }
        if (description != null && description.strip().length() > 500) {
            throw new IllegalArgumentException("简介最多 500 个字符");
        }
        if (content == null || content.isBlank() || content.length() > 8_000) {
            throw new IllegalArgumentException("正文须为 1–8000 个字符");
        }
        return new AgentPreset(id, name.strip(), description == null ? "" : description.strip(), content, enabled);
    }

    private static void validateId(String id) {
        if (!validId(id)) throw new IllegalArgumentException("配置 ID 无效");
    }

    private static boolean validId(String id) { return id != null && id.matches("[a-f0-9]{32}"); }

    public static final class PresetNotFound extends RuntimeException {}

    public static final class InvalidSkillSelection extends IllegalArgumentException {
        public InvalidSkillSelection(String message) { super(message); }
    }
}
