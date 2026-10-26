package com.gaofeng.auth.verification;

import com.gaofeng.auth.config.AuthProperties;
import com.gaofeng.common.exception.BusinessException;
import com.gaofeng.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerificationServiceTest {
    @Mock VerificationCodeStore store;
    @Mock CodeSender sender;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    VerificationService service;

    @BeforeEach void setUp() {
        var properties = new AuthProperties();
        properties.getVerification().setDailyLimit(0);
        service = new VerificationService(store, sender, redis, properties);
    }

    @Test void onlyWinnerOfSendIntervalReservationCanSend() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60))))
                .thenReturn(true, false);
        var result = service.sendCode(VerificationScene.REGISTER, "13800000000");
        assertThat(result.expireSeconds()).isEqualTo(300);
        assertThatThrownBy(() -> service.sendCode(VerificationScene.REGISTER, "13800000000"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VERIFICATION_RATE_LIMIT));
        verify(sender, times(1)).sendCode(eq(VerificationScene.REGISTER), eq("13800000000"), matches("[0-9]{6}"), eq(5));
        verify(store, times(1)).saveCode(eq("REGISTER"), eq("13800000000"), anyString(), eq(Duration.ofMinutes(5)), eq(5));
    }

    @Test void missingReservationResultDoesNotSendCode() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);
        assertThatThrownBy(() -> service.sendCode(VerificationScene.REGISTER, "13800000000"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(store);
        verify(sender, never()).sendCode(any(), anyString(), anyString(), anyInt());
    }

    @Test void failedSendInvalidatesOnlyTheIssuedCodeAndNeverReturnsSuccess() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(sender)
                .sendCode(any(), anyString(), anyString(), anyInt());
        assertThatThrownBy(() -> service.sendCode(VerificationScene.REGISTER, "student@example.com"))
                .isInstanceOf(BusinessException.class);
        var code = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store).saveCode(eq("REGISTER"), eq("student@example.com"), code.capture(), any(), eq(5));
        verify(store).invalidateIfMatches("REGISTER", "student@example.com", code.getValue());
        verify(store, never()).invalidate(anyString(), anyString());
    }

    @Test void missingMailConfigurationDoesNotConsumeRecipientQuotaOrCreateCode() {
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(sender).checkAvailable(anyString());
        assertThatThrownBy(() -> service.sendCode(VerificationScene.LOGIN, "student@example.com"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(store, values);
    }
}
