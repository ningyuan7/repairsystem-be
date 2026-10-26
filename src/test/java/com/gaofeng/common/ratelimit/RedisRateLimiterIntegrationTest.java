package com.gaofeng.common.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Uses only unique integration-test keys with short TTLs; never clears application data. */
@EnabledIfSystemProperty(named = "protection.integration", matches = "true")
class RedisRateLimiterIntegrationTest {
    @Test void concurrentInstancesShareAtomicQuotaAndBlockedRequestsDoNotExtendExpiry() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        var local = Path.of("config/repair-test.yml");
        yaml.setResources(Files.exists(local)
                ? new FileSystemResource[]{new FileSystemResource("src/main/resources/repair.yml"), new FileSystemResource(local)}
                : new FileSystemResource[]{new FileSystemResource("src/main/resources/repair.yml")});
        var properties = yaml.getObject();
        var environment = new StandardEnvironment();
        var config = new RedisStandaloneConfiguration(
                System.getProperty("protection.redis.host", environment.resolveRequiredPlaceholders(System.getenv().getOrDefault("REPAIR_TEST_REDIS_HOST", properties.getProperty("spring.data.redis.host", "localhost")))),
                Integer.parseInt(System.getProperty("protection.redis.port", environment.resolveRequiredPlaceholders(System.getenv().getOrDefault("REPAIR_TEST_REDIS_PORT", properties.getProperty("spring.data.redis.port", "6379"))))));
        String password = environment.resolveRequiredPlaceholders(System.getenv().getOrDefault("REPAIR_TEST_REDIS_PASSWORD", properties.getProperty("spring.data.redis.password", "")));
        password = System.getProperty("protection.redis.password", password);
        if (!password.isBlank()) config.setPassword(password);
        String username = environment.resolveRequiredPlaceholders(System.getenv().getOrDefault("REPAIR_TEST_REDIS_USERNAME", properties.getProperty("spring.data.redis.username", "")));
        username = System.getProperty("protection.redis.username", username);
        if (!username.isBlank()) config.setUsername(username);
        config.setDatabase(15);
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3))
                .shutdownTimeout(Duration.ZERO).clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(3)).build()).build()).build();
        var factory = new LettuceConnectionFactory(config, client);
        factory.afterPropertiesSet(); factory.start();
        try {
            var template = new StringRedisTemplate(factory);
            var first = new RedisRateLimiter(template);
            var second = new RedisRateLimiter(template);
            String bucket = "integration-" + UUID.randomUUID();
            try (var executor = Executors.newFixedThreadPool(8)) {
                var futures = new ArrayList<Future<Boolean>>();
                for (int i = 0; i < 32; i++) {
                    var instance = i % 2 == 0 ? first : second;
                    futures.add(executor.submit(() -> {
                        try { instance.check(bucket, "concurrent", 5, Duration.ofSeconds(10)); return true; }
                        catch (RateLimitException ex) { return false; }
                    }));
                }
                int allowed = 0;
                for (var future : futures) if (future.get()) allowed++;
                assertThat(allowed).isEqualTo(5);
            }
            first.check(bucket, "expiry", 1, Duration.ofMillis(300));
            await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(3))
                    .until(() -> {
                        try { second.check(bucket, "expiry", 1, Duration.ofMillis(300)); return true; }
                        catch (RateLimitException ex) { return false; }
                    });
            verifySingleUseCodes(template);
        } finally { factory.destroy(); }
    }

    private void verifySingleUseCodes(StringRedisTemplate template) throws Exception {
        var codes = new com.gaofeng.auth.verification.RedisVerificationCodeStore(template);
        String recipient = UUID.randomUUID() + "@example.test";
        codes.saveCode("LOGIN", recipient, "123456", Duration.ofSeconds(30), 5);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 32; i++) futures.add(executor.submit(() -> codes.verify("LOGIN", recipient, "123456").isSuccess()));
            int successes = 0;
            for (var future : futures) if (future.get()) successes++;
            assertThat(successes).isEqualTo(1);
        }
        codes.saveCode("LOGIN", recipient, "111111", Duration.ofSeconds(30), 2);
        assertThat(codes.verify("LOGIN", recipient, "000000").status())
                .isEqualTo(com.gaofeng.auth.verification.VerificationCodeStatus.MISMATCH);
        assertThat(codes.verify("LOGIN", recipient, "000000").status())
                .isEqualTo(com.gaofeng.auth.verification.VerificationCodeStatus.TOO_MANY_ATTEMPTS);
        assertThat(codes.verify("LOGIN", recipient, "111111").isSuccess()).isFalse();
        codes.saveCode("LOGIN", recipient, "222222", Duration.ofSeconds(30), 5);
        codes.invalidateIfMatches("LOGIN", recipient, "111111");
        assertThat(codes.verify("LOGIN", recipient, "222222").isSuccess()).isTrue();
        codes.saveCode("LOGIN", recipient, "333333", Duration.ofSeconds(30), 5);
        codes.invalidateIfMatches("LOGIN", recipient, "333333");
        assertThat(codes.verify("LOGIN", recipient, "333333").isSuccess()).isFalse();
    }
}
