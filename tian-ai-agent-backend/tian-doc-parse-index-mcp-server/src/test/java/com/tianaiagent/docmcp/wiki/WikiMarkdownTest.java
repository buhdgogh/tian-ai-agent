package com.tianaiagent.docmcp.wiki;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WikiMarkdownTest {
    @Test void extractsCommonMarkTitleTagsAliasesAndUniqueOutgoingLinks() {
        var page = WikiMarkdown.parse("---\ntags: [医疗, FollowUp]\n---\n# **随访**指南\n\n## 综述\n\n记录症状。[1] #护理\n\n[[page-1|随访]] [[page-1#章节]]\n");
        assertEquals("随访指南", page.title());
        assertEquals(List.of("医疗", "followup", "护理"), page.tags());
        assertEquals(List.of("page-1"), page.links());
        assertEquals(List.of(1), page.sections().getFirst().claimIds());
    }
    @Test void ignoresTagsAndLinksInsideInlineCodeFencesImagesAndHtmlBlocks() {
        var page = WikiMarkdown.parse("# 页面\n\n真实内容。[1] `[[fake]] #fake`\n\n```md\n# 伪造标题\n[[hidden]] #hidden\n```\n\n<div>[[html]] #html</div>\n\n![[image]]\n");
        assertTrue(page.links().isEmpty()); assertTrue(page.tags().isEmpty()); assertEquals("页面", page.title());
    }
    @Test void acceptsSetextHeadingAndRejectsUnsafeMetadataOrExternalWikiTargets() {
        assertEquals("页面", WikiMarkdown.parse("页面\n====\n\n内容。[1]").title());
        assertThrows(IllegalArgumentException.class, () -> WikiMarkdown.parse("---\ntags: !!java.lang.Runtime {}\n---\n# 页面\n"));
        assertThrows(IllegalArgumentException.class, () -> WikiMarkdown.parse("# 页面\n\n[[../../secret]]"));
        assertThrows(IllegalArgumentException.class, () -> WikiMarkdown.parse("# 页面\n\n[[https://example.com]]"));
        assertThrows(IllegalArgumentException.class, () -> WikiMarkdown.parse("# A\n\n# B\n"));
    }
}
