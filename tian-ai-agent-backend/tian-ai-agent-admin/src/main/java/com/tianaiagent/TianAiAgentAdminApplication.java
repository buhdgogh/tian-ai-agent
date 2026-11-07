package com.tianaiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class TianAiAgentAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(TianAiAgentAdminApplication.class, args);
        System.out.println("""

                ╔═══════════════════════════════════════════════════════════════╗
                ║          天医 - 循证医疗信息与安全支持智能体已启动                  ║
                ╚═══════════════════════════════════════════════════════════════╝
                """);
    }
}
