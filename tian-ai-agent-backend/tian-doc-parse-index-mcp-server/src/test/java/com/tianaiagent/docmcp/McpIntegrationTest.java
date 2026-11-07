package com.tianaiagent.docmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp-test;DB_CLOSE_DELAY=-1",
        "doc-mcp.vector-store=local",
        "doc-mcp.wiki.output-dir=./work/mcp-wiki-test-files",
        "doc-mcp.embedding.provider=local",
        "doc-mcp.embedding.dimensions=128",
        "doc-mcp.chunk-sizes=64,24,10"
})
@AutoConfigureMockMvc
class McpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void explicitRetrievalModesAreHonoredAndLocalModeCannotClaimBm25() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":101,"method":"tools/call","params":{"name":"retrieve_knowledge","arguments":{"question":"检索资料","retrieval_mode":"dense"}}}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content[0].text", containsString("h2-float32-dense-mmr")));
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":102,"method":"tools/call","params":{"name":"retrieve_knowledge","arguments":{"question":"检索资料","retrieval_mode":"keyword"}}}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.content[0].text", containsString("invalid_argument")));
    }

    @Test
    void initializesListsToolsAndIndexesMarkdown() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("Mcp-Session-Id"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("tian-doc-parse-index-mcp-server"));

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(24));

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"index_document","arguments":{"doc_id":"geo-1","text":"# 地质报告\\n\\n花岗岩主要分布在研究区北部。","title":"地质报告"}}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString("leaf_node_count")));

        MvcResult retrieval = mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"retrieve_knowledge","arguments":{"question":"花岗岩分布在哪里","top_k":3}}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString("geo-1")))
                .andReturn();

        JsonNode retrievalEnvelope = mapper.readTree(retrieval.getResponse().getContentAsString());
        JsonNode retrievalData = mapper.readTree(retrievalEnvelope.at("/result/content/0/text").asText());
        String chunkId = retrievalData.at("/data/sources/0/chunkId").asText();
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":41,"method":"tools/call","params":{"name":"get_chunk_context","arguments":{"chunk_id":"%s"}}}
                """.formatted(chunkId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString(chunkId)))
                .andExpect(jsonPath("$.result.content[0].text", containsString("geo-1")));

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"index_document","arguments":{"doc_id":"geo-1","text":"# 地质报告\\n\\n玄武岩主要分布在研究区南部。","title":"新版地质报告"}}}
                """))
                .andExpect(status().isOk());

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"retrieve_knowledge","arguments":{"question":"玄武岩分布在哪里","top_k":3}}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString("玄武岩")))
                .andExpect(jsonPath("$.result.content[0].text", not(containsString("花岗岩主要分布"))));

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"delete_document","arguments":{"doc_id":"geo-1"}}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString("deleted")));

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"upsert_long_term_memory","arguments":{"owner_id":"medical-agent:chat1","memory_id":"summary_1","text":"用户正在整理高血压随访资料","metadata":{"type":"conversation_summary"}}}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString("stored")));

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"search_long_term_memory","arguments":{"owner_id":"medical-agent:chat1","query":"高血压随访","top_k":3}}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString("高血压随访资料")));

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"delete_long_term_memory","arguments":{"owner_id":"medical-agent:chat1"}}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString("\"deleted\":true")));

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("""
                {"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"search_long_term_memory","arguments":{"owner_id":"medical-agent:chat1","query":"高血压随访","top_k":3}}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString("\"count\":0")));
    }
}
