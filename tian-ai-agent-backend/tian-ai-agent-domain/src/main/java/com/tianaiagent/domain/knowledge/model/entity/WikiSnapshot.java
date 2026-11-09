package com.tianaiagent.domain.knowledge.model.entity;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public record WikiSnapshot(String id, String builtAt, Map<String, String> sourceRevisions, List<Page> pages,
                           String consolidationWarning) {
    public enum Kind {
        ENTITIES, CONCEPTS, SYNTHESES;

        public String directory() { return name().toLowerCase(Locale.ROOT); }
    }

    public record Page(String id, Kind kind, String title, String body, List<String> sourceIds, List<String> targets) {
        public String path() { return kind.directory() + "/" + id + ".md"; }

        public String markdown(Map<String, Page> pages, Map<String, String> sources) {
            var text = new StringBuilder("# ").append(title).append("\n\n").append(body).append("\n\n## 原始资料\n");
            sourceIds.forEach(source -> text.append("- ").append(sources.getOrDefault(source, source))
                    .append(" (`").append(source).append("`)\n"));
            var links = targets.stream().map(pages::get).filter(java.util.Objects::nonNull).toList();
            if (!links.isEmpty()) {
                text.append("\n## 相关页面\n");
                links.forEach(target -> text.append("- [[").append(target.path().replaceFirst("\\.md$", ""))
                        .append('|').append(target.title()).append("]]\n"));
            }
            return text.toString();
        }
    }
}
