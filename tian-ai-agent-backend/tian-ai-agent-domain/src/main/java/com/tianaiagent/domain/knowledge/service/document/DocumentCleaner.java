package com.tianaiagent.domain.knowledge.service.document;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 清理解析产物中的高置信噪声，保留原文的 Markdown 结构和有意义的重复。 */
public final class DocumentCleaner {
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0b\\x0e-\\x1f\\x7f\\ufeff]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern FENCE = Pattern.compile("^ {0,3}([\u0060]{3,}|~{3,})(.*)$");
    private static final Pattern HTML_BLOCK = Pattern.compile("^\\s*<(table|pre|code|math|svg|figure)(?:\\s|>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN = Pattern.compile("^(?:#{1,6}\\s|[>\\[!<]|[-+*]\\s|\\d+[.)]\\s|[-*_＝=]{3,}$)");
    private static final Pattern PROVENANCE = Pattern.compile(
            "^(?:(?:版本|修订日期|发布日期|生效日期|作者|来源|版权|version|revision|source)\\s*[:：]|copyright\\b|©)");
    private static final Pattern PAGE_LABEL = Pattern.compile(
            "^(?:第\\s*\\d{1,5}\\s*页(?:\\s*[/／]\\s*(?:共\\s*)?\\d{1,5}\\s*页?)?|page\\s+\\d{1,5}(?:\\s*(?:of|/)\\s*\\d{1,5})?)$");
    private static final Pattern NUMBER = Pattern.compile("^\\d{1,5}$");
    private static final Pattern EXPLICIT_WATERMARK = Pattern.compile("^(?:水印|watermark)\\s*[:：]\\s*\\S.{0,72}$");
    private static final Set<String> WATERMARKS = Set.of("机密", "绝密", "保密", "内部资料", "仅供内部使用", "请勿外传",
            "严禁外传", "草稿", "样稿", "试读版", "confidential", "internal use only", "do not distribute", "draft");
    private static final int EDGE_LINES = 2;
    private static final int MIN_DUPLICATE_LENGTH = 24;

    private DocumentCleaner() {}

    public static Result clean(String raw) {
        String normalized = CONTROL.matcher(raw.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
        List<Page> pages = pages(normalized);
        Set<String> interior = new HashSet<>();
        Map<String, Integer> topCounts = new HashMap<>();
        Map<String, Integer> bottomCounts = new HashMap<>();
        Map<Integer, Integer> topNumbers = new HashMap<>();
        Map<Integer, Integer> bottomNumbers = new HashMap<>();
        Map<String, Integer> watermarks = new HashMap<>();
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            Page page = pages.get(pageIndex);
            countMargins(page, page.top(), topCounts);
            countMargins(page, page.bottom(), bottomCounts);
            countNumber(page, page.outerTop(), pageIndex, topNumbers);
            countNumber(page, page.outerBottom(), pageIndex, bottomNumbers);
            Set<String> pageWatermarks = new HashSet<>();
            for (int index = 0; index < page.lines().size(); index++) {
                Line line = page.lines().get(index);
                if (line.protectedContent() || line.key().isEmpty()) continue;
                if (!page.edge(index)) interior.add(line.key());
                if (WATERMARKS.contains(line.key()) && isolated(page, index)) {
                    pageWatermarks.add(line.key());
                }
            }
            pageWatermarks.forEach(key -> watermarks.merge(key, 1, Integer::sum));
        }

        int threshold = Math.max(3, (int) Math.ceil(pages.size() * 0.6));
        int removedMargins = 0, removedNumbers = 0, removedWatermarks = 0;
        var kept = new ArrayList<Line>();
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            Page page = pages.get(pageIndex);
            for (int index = 0; index < page.lines().size(); index++) {
                Line line = page.lines().get(index);
                String key = line.key();
                if (!line.protectedContent() && !key.isEmpty()) {
                    if (isolated(page, index) && (EXPLICIT_WATERMARK.matcher(key).matches()
                            || watermarks.getOrDefault(key, 0) >= threshold)) {
                        removedWatermarks++;
                        continue;
                    }
                    if (pages.size() > 1 && page.edge(index)
                            && (PAGE_LABEL.matcher(key).matches()
                            || repeatedNumber(key, index, page, pageIndex, threshold, topNumbers, bottomNumbers))) {
                        removedNumbers++;
                        continue;
                    }
                    if (!interior.contains(key)
                            && (page.top().contains(index) && topCounts.getOrDefault(key, 0) >= threshold
                            || page.bottom().contains(index) && bottomCounts.getOrDefault(key, 0) >= threshold)) {
                        removedMargins++;
                        continue;
                    }
                }
                kept.add(line);
            }
            if (pageIndex + 1 < pages.size()) kept.add(new Line("", "", false));
        }

        var blocks = new ArrayList<String>();
        var block = new ArrayList<Line>();
        String previous = "";
        int duplicateBlocks = 0;
        for (int index = 0; index <= kept.size(); index++) {
            if (index < kept.size()) {
                Line line = kept.get(index);
                if (line.protectedContent() || !line.text().isBlank()) {
                    block.add(line);
                    continue;
                }
            }
            if (block.isEmpty()) continue;
            String value = String.join("\n", block.stream().map(Line::text).toList());
            String key = block.stream().anyMatch(Line::protectedContent) ? "" : WHITESPACE.matcher(value).replaceAll(" ").strip();
            if (key.length() >= MIN_DUPLICATE_LENGTH && key.equals(previous)) {
                duplicateBlocks++;
            } else {
                blocks.add(value);
                previous = key;
            }
            block.clear();
        }
        String markdown = String.join("\n\n", blocks);
        return new Result(markdown, raw.length(), pages.size(), removedMargins, removedNumbers, removedWatermarks, duplicateBlocks);
    }

    private static List<Page> pages(String markdown) {
        var result = new ArrayList<Page>();
        var structure = new Structure();
        for (String section : markdown.split("\\f", -1)) {
            if (section.isBlank()) continue;
            var lines = new ArrayList<Line>();
            var nonblank = new ArrayList<Integer>();
            for (String text : section.split("\n", -1)) {
                String key = WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll(" ")
                        .strip().toLowerCase(Locale.ROOT);
                if (!key.isEmpty()) nonblank.add(lines.size());
                lines.add(new Line(text, key, structure.protect(text, key)));
            }
            int edge = Math.min(EDGE_LINES, Math.max(0, (nonblank.size() - 1) / 2));
            result.add(new Page(lines, nonblank.subList(0, edge), nonblank.subList(nonblank.size() - edge, nonblank.size())));
        }
        return result;
    }

    private static void countMargins(Page page, List<Integer> positions, Map<String, Integer> counts) {
        positions.stream().map(page.lines()::get)
                .filter(line -> !line.protectedContent() && line.key().length() >= 3 && line.key().length() <= 100)
                .map(Line::key).filter(key -> !NUMBER.matcher(key).matches()).distinct()
                .forEach(key -> counts.merge(key, 1, Integer::sum));
    }

    private static void countNumber(Page page, int position, int pageIndex, Map<Integer, Integer> counts) {
        if (position < 0) return;
        Line line = page.lines().get(position);
        if (!line.protectedContent() && NUMBER.matcher(line.key()).matches()) {
            counts.merge(Integer.parseInt(line.key()) - pageIndex, 1, Integer::sum);
        }
    }

    private static boolean repeatedNumber(String key, int index, Page page, int pageIndex, int threshold,
                                          Map<Integer, Integer> topNumbers, Map<Integer, Integer> bottomNumbers) {
        if (!NUMBER.matcher(key).matches()) return false;
        int offset = Integer.parseInt(key) - pageIndex;
        return index == page.outerTop() && topNumbers.getOrDefault(offset, 0) >= threshold
                || index == page.outerBottom() && bottomNumbers.getOrDefault(offset, 0) >= threshold;
    }

    private static boolean isolated(Page page, int index) {
        return page.edge(index) || (index == 0 || page.lines().get(index - 1).text().isBlank())
                && (index + 1 == page.lines().size() || page.lines().get(index + 1).text().isBlank());
    }

    private record Line(String text, String key, boolean protectedContent) {}
    private record Page(List<Line> lines, List<Integer> top, List<Integer> bottom) {
        boolean edge(int index) { return top.contains(index) || bottom.contains(index); }
        int outerTop() { return top.isEmpty() ? -1 : top.getFirst(); }
        int outerBottom() { return bottom.isEmpty() ? -1 : bottom.getLast(); }
    }

    private static final class Structure {
        private String fence;
        private String blockEnd;

        private boolean protect(String text, String key) {
            String trimmed = text.strip();
            var marker = FENCE.matcher(text);
            if (fence != null) {
                if (marker.matches() && marker.group(1).charAt(0) == fence.charAt(0)
                        && marker.group(1).length() >= fence.length() && marker.group(2).isBlank()) fence = null;
                return true;
            }
            if (blockEnd != null) {
                if (key.contains(blockEnd)) blockEnd = null;
                return true;
            }
            if (marker.matches()) {
                fence = marker.group(1);
                return true;
            }
            if (trimmed.equals("$$") || trimmed.equals("\\[")) {
                blockEnd = trimmed.equals("$$") ? "$$" : "\\]";
                return true;
            }
            var html = HTML_BLOCK.matcher(text);
            if (html.find()) {
                String end = "</" + html.group(1).toLowerCase(Locale.ROOT) + ">";
                if (!key.contains(end)) blockEnd = end;
                return true;
            }
            return !trimmed.isEmpty() && (text.startsWith("    ") || text.startsWith("\t")
                    || MARKDOWN.matcher(trimmed).find() || PROVENANCE.matcher(key).find()
                    || trimmed.indexOf('|') >= 0 || trimmed.contains("$") || trimmed.contains("\u0060"));
        }
    }

    public record Result(String markdown, int originalChars, int pageSections, int removedMarginLines, int removedPageNumbers,
                         int removedWatermarkLines, int removedDuplicateBlocks) {
        public Map<String, Integer> statistics() {
            return Map.of("originalChars", originalChars, "cleanedChars", markdown.length(), "pageSections", pageSections,
                    "removedMarginLines", removedMarginLines, "removedPageNumbers", removedPageNumbers,
                    "removedWatermarkLines", removedWatermarkLines, "removedDuplicateBlocks", removedDuplicateBlocks);
        }
    }
}
