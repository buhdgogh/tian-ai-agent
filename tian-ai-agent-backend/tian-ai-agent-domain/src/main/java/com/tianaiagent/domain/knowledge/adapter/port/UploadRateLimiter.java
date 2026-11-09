package com.tianaiagent.domain.knowledge.adapter.port;

public interface UploadRateLimiter {
    RateLimitResult tryAcquire(String clientIp);

    record RateLimitResult(boolean allowed, int remaining, int retryAfterSeconds) {}
}
