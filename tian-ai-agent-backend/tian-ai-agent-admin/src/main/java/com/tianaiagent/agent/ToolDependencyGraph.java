package com.tianaiagent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根据工具参数中的显式结果引用构建调用依赖图。
 * 引用格式：{{tool:0#/data/id}}，0 是本轮工具调用序号，# 后为 JSON Pointer。
 */
final class ToolDependencyGraph {
    private static final Pattern REFERENCE = Pattern.compile("\\{\\{tool:(\\d+)(#[^{}]*)?}}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Set<Integer>> dependencies;

    private ToolDependencyGraph(List<Set<Integer>> dependencies) {
        this.dependencies = dependencies;
    }

    static ToolDependencyGraph from(List<AssistantMessage.ToolCall> calls) {
        List<Set<Integer>> dependencies = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            Set<Integer> current = extractReferences(calls.get(index).arguments());
            for (int dependency : current) {
                if (dependency < 0 || dependency >= calls.size()) {
                    throw new IllegalArgumentException("工具 " + index + " 引用了不存在的工具 " + dependency);
                }
                if (dependency == index) {
                    throw new IllegalArgumentException("工具 " + index + " 不能引用自身结果");
                }
            }
            dependencies.add(Set.copyOf(current));
        }
        validateAcyclic(dependencies);
        return new ToolDependencyGraph(List.copyOf(dependencies));
    }

    boolean hasDependencies() {
        return dependencies.stream().anyMatch(items -> !items.isEmpty());
    }

    Set<Integer> dependenciesOf(int index) {
        return dependencies.get(index);
    }

    List<Integer> ready(Set<Integer> completed) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < dependencies.size(); index++) {
            if (!completed.contains(index) && completed.containsAll(dependencies.get(index))) {
                result.add(index);
            }
        }
        return result;
    }

    String resolveArguments(int callIndex, String arguments, Map<Integer, String> results) {
        if (dependencies.get(callIndex).isEmpty()) return arguments;
        try {
            JsonNode root = MAPPER.readTree(arguments);
            if (root == null) throw new IllegalArgumentException("工具 " + callIndex + " 参数不能为空");
            JsonNode resolved = resolveNode(root, results);
            return MAPPER.writeValueAsString(resolved);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具 " + callIndex + " 参数不是合法 JSON", e);
        }
    }

    private JsonNode resolveNode(JsonNode node, Map<Integer, String> results) throws JsonProcessingException {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) object.set(field, resolveNode(object.get(field), results));
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, resolveNode(array.get(index), results));
            }
            return array;
        }
        if (!node.isTextual()) return node;

        String text = node.asText();
        Matcher exact = REFERENCE.matcher(text);
        if (exact.matches()) return resolveReference(exact, results);

        Matcher embedded = REFERENCE.matcher(text);
        StringBuilder value = new StringBuilder();
        while (embedded.find()) {
            JsonNode replacement = resolveReference(embedded, results);
            embedded.appendReplacement(value, Matcher.quoteReplacement(
                    replacement.isTextual() ? replacement.asText() : replacement.toString()));
        }
        embedded.appendTail(value);
        return TextNode.valueOf(value.toString());
    }

    private JsonNode resolveReference(Matcher reference, Map<Integer, String> results)
            throws JsonProcessingException {
        int dependency = Integer.parseInt(reference.group(1));
        String rawResult = results.get(dependency);
        if (rawResult == null) {
            throw new IllegalArgumentException("依赖工具 " + dependency + " 尚未产生结果");
        }
        String pointer = reference.group(2);
        if (pointer == null || pointer.length() == 1) return TextNode.valueOf(rawResult);
        JsonNode selected = MAPPER.readTree(rawResult).at(pointer.substring(1));
        if (selected.isMissingNode()) {
            throw new IllegalArgumentException("依赖工具 " + dependency + " 的结果不存在路径 " + pointer.substring(1));
        }
        return selected.deepCopy();
    }

    private static Set<Integer> extractReferences(String arguments) {
        Set<Integer> result = new LinkedHashSet<>();
        Matcher matcher = REFERENCE.matcher(arguments == null ? "" : arguments);
        while (matcher.find()) result.add(Integer.parseInt(matcher.group(1)));
        return result;
    }

    private static void validateAcyclic(List<Set<Integer>> dependencies) {
        int[] indegrees = dependencies.stream().mapToInt(Set::size).toArray();
        List<List<Integer>> dependents = new ArrayList<>(dependencies.size());
        for (int index = 0; index < dependencies.size(); index++) dependents.add(new ArrayList<>());
        for (int node = 0; node < dependencies.size(); node++) {
            for (int dependency : dependencies.get(node)) dependents.get(dependency).add(node);
        }
        ArrayDeque<Integer> ready = new ArrayDeque<>();
        for (int index = 0; index < indegrees.length; index++) if (indegrees[index] == 0) ready.add(index);
        int visited = 0;
        while (!ready.isEmpty()) {
            int current = ready.removeFirst();
            visited++;
            for (int dependent : dependents.get(current)) {
                if (--indegrees[dependent] == 0) ready.addLast(dependent);
            }
        }
        if (visited != dependencies.size()) throw new IllegalArgumentException("工具调用参数中存在循环依赖");
    }
}
