package com.tianaiagent.docmcp;

import com.tianaiagent.docmcp.config.DocMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.http.HttpClient;
import java.time.Duration;

@SpringBootApplication
@EnableConfigurationProperties(DocMcpProperties.class)
@EnableScheduling
public class DocParseIndexMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocParseIndexMcpApplication.class, args);
    }

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
