package com.tianaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.types.config.DocMcpProperties;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EmbeddedMcpConfigurationTest {
    private final EmbeddedMcpConfiguration configuration = new EmbeddedMcpConfiguration();

    @Test
    void supportsAnExplicitAccountTokenAndRespectsTheFeatureFlag() {
        var properties = new DocMcpProperties();
        var client = configuration.mineruClient(properties, mock(HttpClient.class), new ObjectMapper())
                .withToken("account-mineru-token");

        assertThat(client.isAvailable()).isTrue();
        assertThat(properties.getMineru().getToken()).isEmpty();
        properties.getMineru().setEnabled(false);
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void permitsAnAccountWithoutTokenEvenWhenTheServerHasOne() {
        var properties = new DocMcpProperties();
        properties.getMineru().setToken("test-direct-token");
        var client = configuration.mineruClient(properties, mock(HttpClient.class), new ObjectMapper());
        assertThat(client.withToken("").isAvailable()).isFalse();
        assertThat(client.withToken("account-mineru-token").isAvailable()).isTrue();
        assertThat(client.withToken("").isAvailable()).isFalse();
    }
}
