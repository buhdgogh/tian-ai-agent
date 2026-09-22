package com.tianaiagent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchToolTest
{
    @Test
    void reportsMissingCredentialWithoutNetworkCall()
    {
        String result = new WebSearchTool("").searchWeb("高血压指南 最新版本");
        assertThat(result).contains("SEARCH_API_KEY", "\"ok\":false");
    }
}
