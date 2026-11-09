package com.tianaiagent.trigger.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 描述：健康控制器层
 */
@RestController
@RequestMapping("/health")
public class HealthController {
    @GetMapping
    public String healthCheck()
    {
        return "ok";
    }
}
