package com.tianaiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        return template;
    }

    @Bean
    public DefaultRedisScript<Long> rateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
            -- KEYS[1]: 限流 key (kb:upload:rate:{ip})
            -- ARGV[1]: 当前时间戳 (毫秒)
            -- ARGV[2]: 滑动窗口大小 (毫秒)
            -- ARGV[3]: 窗口内最大请求数
            -- ARGV[4]: 当前请求的唯一标识 (timestamp + random)

            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]

            -- 1. 移除窗口外的过期记录（滑动窗口的核心：只保留最近 window 毫秒内的记录）
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

            -- 2. 统计当前窗口内的请求数
            local count = redis.call('ZCARD', key)

            -- 3. 判断是否超限
            if count < limit then
                -- 未超限：记录本次请求
                redis.call('ZADD', key, now, member)
                -- 设置 key 过期时间，避免冷数据残留
                redis.call('EXPIRE', key, math.ceil(window / 1000) + 1)
                return limit - count            -- 正数：允许；返回值减 1 为剩余容量
            else
                return 0                        -- 0：拒绝
            end
            """);
        return script;
    }
}
