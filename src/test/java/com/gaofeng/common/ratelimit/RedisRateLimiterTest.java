package com.gaofeng.common.ratelimit;

import com.gaofeng.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisRateLimiterTest {
    @Test void redisFailureOrNoResultNeverAllowsRequestsThrough() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> new RedisRateLimiter(redis).check("login", "phone", 10, Duration.ofMinutes(1)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("暂不可用");
        reset(redis);
        assertThatThrownBy(() -> new RedisRateLimiter(redis).check("login", "phone", 10, Duration.ofMinutes(1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test void retryIntervalIsRoundedUpAndRawIdentifiersAreNotStoredInKeys() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1001L);
        assertThatThrownBy(() -> new RedisRateLimiter(redis).check("login", "13800000000", 10, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(RateLimitException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(2));
        var keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue().getFirst().toString()).startsWith("abuse:login:")
                .doesNotContain("13800000000");
    }
}
