package com.gaofeng.common.ratelimit;

import com.gaofeng.common.exception.BusinessException;
import com.gaofeng.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** Shared, atomic fixed windows. Redis keys never contain raw account identifiers or IPs. */
@Service
@RequiredArgsConstructor
public class RedisRateLimiter {
    static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count >= tonumber(ARGV[1]) then
                return math.max(1, redis.call('PTTL', KEYS[1]))
            end
            local next = redis.call('INCR', KEYS[1])
            if next == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;

    public void check(String bucket, String subject, int limit, Duration window) {
        if (limit < 1 || window.isNegative() || window.isZero())
            throw new IllegalArgumentException("Rate limits must be positive");
        Long wait;
        try {
            wait = redis.execute(SCRIPT, List.of("abuse:" + bucket + ":" + hash(subject)),
                    Integer.toString(limit), Long.toString(window.toMillis()));
        } catch (DataAccessException ex) {
            var unavailable = new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "请求保护服务暂不可用，请稍后重试");
            unavailable.initCause(ex);
            throw unavailable;
        }
        if (wait == null) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        if (wait > 0) throw new RateLimitException((wait + 999) / 1000);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
