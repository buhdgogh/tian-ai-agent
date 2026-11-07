package com.tianaiagent.docmcp.wiki;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.*;
import java.util.regex.Pattern;

/** CommonMark AST excludes code blocks, inline code and HTML from page metadata extraction. */
public final class WikiMarkdown {
    private static final Parser PARSER = Parser.builder().build();
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[\\[([^\\]\\n]+)]]");
    private static final Pattern TAG = Pattern.compile("(?<![\\p{L}\\p{N}_/#])#([\\p{L}\\p{N}_][\\p{L}\\p{N}_/-]{0,79})");
    private static final Pattern CITE = Pattern.compile("(?<!\\[)\\[(\\d+)](?![\\]\\[(])");
    private WikiMarkdown() {}

    public static Document parse(String markdown) {
        if (markdown == null || markdown.isBlank() || markdown.length() > 200000) throw new IllegalArgumentException("Markdown不能为空且最多200000字符");
        String body = markdown.replace("\r\n", "\n"); Map<?, ?> front = Map.of();
        if (body.startsWith("---\n")) {
            int end = body.indexOf("\n---\n", 4);
            if (end < 0 || end > 8192) throw new IllegalArgumentException("Wiki frontmatter未闭合或过长");
            LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0); options.setCodePointLimit(8192);
            Object yaml;
            try { yaml = new Yaml(new SafeConstructor(options)).load(body.substring(4, end)); }
            catch (org.yaml.snakeyaml.error.YAMLException e) { throw new IllegalArgumentException("Wiki frontmatter格式不合法"); }
            if (!(yaml instanceof Map<?, ?> map)) throw new IllegalArgumentException("Wiki frontmatter必须是对象");
            front = map; body = body.substring(end + 5);
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Object rawTags = front.get("tags");
        if (rawTags instanceof Collection<?> values) values.forEach(v -> addTag(tags, String.valueOf(v)));
        else if (rawTags instanceof String value) addTag(tags, value);
        else if (rawTags != null) throw new IllegalArgumentException("tags必须是字符串或数组");
        LinkedHashSet<String> links = new LinkedHashSet<>(); List<String> headings = new ArrayList<>();
        List<WikiModels.Section> sections = new ArrayList<>();
        Node root = PARSER.parse(body); String title = "";
        String heading = "概述"; StringBuilder content = new StringBuilder();
        for (Node block = root.getFirstChild(); block != null; block = block.getNext()) {
            String text = visibleText(block);
            if (block instanceof Heading h) {
                if (h.getLevel() == 1) {
                    if (!title.isEmpty()) throw new IllegalArgumentException("Wiki只允许一个一级标题");
                    title = text.trim();
                } else {
                    addSection(sections, heading, content); heading = text.trim(); headings.add(heading);
                }
            } else if (!text.isBlank()) content.append(text).append('\n');
            if (!(block instanceof Heading)) {
                var tag = TAG.matcher(LINK.matcher(text).replaceAll("")); while (tag.find()) addTag(tags, tag.group(1));
            }
            var link = LINK.matcher(text);
            while (link.find()) {
                String target = link.group(1).split("\\|", 2)[0].split("#", 2)[0].trim();
                if (target.isEmpty() || target.length() > 300 || target.contains(":") || target.contains("/") || target.contains("\\") || target.equals(".."))
                    throw new IllegalArgumentException("Wiki链接必须是页面ID或标题");
                links.add(target);
            }
        }
        addSection(sections, heading, content);
        if (title.isBlank() && front.get("title") instanceof String value) title = value.trim();
        if (title.isBlank() || title.length() > 500) throw new IllegalArgumentException("Markdown缺少有效标题");
        if (tags.size() > 32 || links.size() > 200) throw new IllegalArgumentException("标签或链接数量超限");
        String category = front.get("category") instanceof String value ? value.trim().toUpperCase(Locale.ROOT) : "";
        return new Document(title, List.copyOf(tags), List.copyOf(links), List.copyOf(headings), List.copyOf(sections), body, category);
    }

    private static String visibleText(Node node) {
        if (node instanceof Code || node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock || node instanceof HtmlBlock || node instanceof HtmlInline || node instanceof Image) return "";
        if (node instanceof Text text) return text.getLiteral();
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak) return "\n";
        StringBuilder out = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) out.append(visibleText(child));
        return out.toString();
    }
    private static void addTag(Set<String> tags, String raw) {
        String tag = raw.trim().replaceFirst("^#", "").toLowerCase(Locale.ROOT);
        if (!tag.matches("[\\p{L}\\p{N}_][\\p{L}\\p{N}_/-]{0,79}")) throw new IllegalArgumentException("非法Wiki标签");
        tags.add(tag);
    }
    private static void addSection(List<WikiModels.Section> sections, String heading, StringBuilder content) {
        String text = content.toString().trim(); content.setLength(0);
        if (text.isEmpty() || text.replaceAll("\\[\\[[^]]+]]", "").replaceAll("#[\\p{L}\\p{N}_/-]+", "").isBlank()) return;
        LinkedHashSet<Integer> citations = new LinkedHashSet<>(); var matcher = CITE.matcher(text);
        while (matcher.find()) { try { citations.add(Integer.parseInt(matcher.group(1))); } catch (NumberFormatException e) { throw new IllegalArgumentException("非法引用编号"); } }
        sections.add(new WikiModels.Section(heading, text, List.copyOf(citations)));
    }
    public record Document(String title, List<String> tags, List<String> links, List<String> headings, List<WikiModels.Section> sections, String body, String category) {}
}
