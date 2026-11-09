package com.tianaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.types.config.DocMcpProperties;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedMcpConfigurationTest {
    private final EmbeddedMcpConfiguration configuration = new EmbeddedMcpConfiguration();

    @Test
    void usesMineruTokenFromDotEnvWhenTheDirectPropertyIsMissing() {
        var properties = new DocMcpProperties();
        var client = configuration.mineruClient(properties, HttpClient.newHttpClient(),
                new ObjectMapper(), "test-dotenv-token");

        assertThat(client.isAvailable()).isTrue();
        assertThat(properties.getMineru().getToken()).isEqualTo("test-dotenv-token");
        properties.getMineru().setEnabled(false);
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void givesTheDirectPropertyPriorityAndAllowsNoToken() {
        var properties = new DocMcpProperties();
        var http = HttpClient.newHttpClient();
        var mapper = new ObjectMapper();
        assertThat(configuration.mineruClient(properties, http, mapper, "").isAvailable()).isFalse();

        properties.getMineru().setToken("test-direct-token");
        var client = configuration.mineruClient(properties, http, mapper, "test-dotenv-token");
        assertThat(client.isAvailable()).isTrue();
        assertThat(properties.getMineru().getToken()).isEqualTo("test-direct-token");
    }
}
