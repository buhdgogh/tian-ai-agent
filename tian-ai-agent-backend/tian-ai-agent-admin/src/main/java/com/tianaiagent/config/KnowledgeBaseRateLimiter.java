package com.tianaiagent.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.UUID;

/**
 * 知识库上传滑动窗口限流器
 *
 * 基于 Redis ZSET + Lua 实现原子化的滑动窗口限流。
 * 每个 IP 独立计数，窗口内超过阈值返回 false。
 *
 * 为什么是"排队式"的效果：
 * 1. 窗口内的请求按时间戳分布，旧请求过期后自动释放容量
 * 2. 被拒绝的请求返回明确的 429 + Retry-After，前端可自动重试
 * 3. 相比硬拒绝，滑动窗口保证了"先进先出"的时间顺序公平性
 */
@Component
@Slf4j
public class KnowledgeBaseRateLimiter {

    private static final String KEY_PREFIX = "kb:upload:rate:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private DefaultRedisScript<Long> rateLimitScript;

    @Value("${knowledge-base.upload.max-per-window:10}")
    private int maxPerWindow;

    @Value("${knowledge-base.upload.window-seconds:60}")
    private int windowSeconds;

    /**
     * 尝试获取上传许可
     *
     * @param request HTTP 请求（用于提取客户端 IP）
     * @return RateLimitResult 包含是否允许、剩余容量、重试等待时间
     */
    public RateLimitResult tryAcquire(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        String key = KEY_PREFIX + clientIp;
        long now = System.currentTimeMillis();
        long windowMs = windowSeconds * 1000L;
        String member = now + ":" + UUID.randomUUID().toString().substring(0, 8);

        // 当前的滑动窗口限流是基于客户端ip进行的限流
        // List.of(key) 用来标识这是哪个客户端的限流窗口
        /**
         * ZSET 的 member（唯一标识每次请求，滑动窗口的按照时间进行删除0，同一时刻可能有多个请求，member 不影响删除逻辑，但保证了每个元素唯一）
         * 假设 IP 192.168.1.100 在 60 秒内发了 3 次请求：
         * 请求	时间戳（score）	member
         * 第 1 次	1694567890000	1694567890000:a1b2c3d4
         * 第 2 次	1694567891000	1694567891000:e5f6g7h8
         * 第 3 次	1694567892000	1694567892000:i9j0k1l2
         */
        Long result = stringRedisTemplate.execute(  // result > 0 表示允许，返回剩余容量。
                rateLimitScript,
                List.of(key),  // 确定 ZSET 是哪个（即操作哪个 Key，区分不同的滑动窗口）
                String.valueOf(now),  // 作为 ZSET 的 score（对窗口内的请求按时间排序）
                String.valueOf(windowMs),
                String.valueOf(maxPerWindow),
                member
        );

        boolean allowed = result != null && result > 0;
        long remaining = allowed ? result - 1 : 0;

        if (!allowed) {
            log.warn("上传限流触发 | IP: {} | 窗口内请求数已达上限 {}", clientIp, maxPerWindow);
        }

        return new RateLimitResult(allowed, (int) remaining, windowSeconds);
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For 可能包含多个 IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    public record RateLimitResult(boolean allowed, int remaining, int retryAfterSeconds) {}
}
