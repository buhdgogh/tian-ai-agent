package com.tianaiagent;

import com.alibaba.cloud.ai.autoconfigure.dashscope.*;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class,
        DashScopeChatAutoConfiguration.class, DashScopeEmbeddingAutoConfiguration.class, DashScopeImageAutoConfiguration.class,
        DashScopeAudioSpeechAutoConfiguration.class, DashScopeAudioTranscriptionAutoConfiguration.class,
        DashScopeRerankAutoConfiguration.class, DashScopeAgentAutoConfiguration.class})
public class TianAiAgentAdminApplication {
    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        SpringApplication.run(TianAiAgentAdminApplication.class, args);
        System.out.println("Tian 知识工作台已启动");
    }
}
