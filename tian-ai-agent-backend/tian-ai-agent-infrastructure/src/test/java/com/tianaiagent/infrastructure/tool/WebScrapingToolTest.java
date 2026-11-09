package com.tianaiagent.infrastructure.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebScrapingToolTest
{
    @Test
    void blocksLoopbackAndPrivateEvidenceUrls()
    {
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        String result = webScrapingTool.scrapeWebPage("http://127.0.0.1/private-record");
        assertThat(result).contains("禁止访问本机或内网地址", "\"ok\":false");
    }
}
