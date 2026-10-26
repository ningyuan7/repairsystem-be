package com.gaofeng.repair;

import com.gaofeng.auth.service.AuthService;

import com.gaofeng.auth.api.dto.*;
import com.gaofeng.auth.audit.LoginLogService;
import com.gaofeng.auth.config.AuthProperties;
import com.gaofeng.auth.model.ClientInfo;
import com.gaofeng.auth.model.IdentifierType;
import com.gaofeng.auth.token.*;
import com.gaofeng.auth.verification.*;
import com.gaofeng.common.exception.BusinessException;
import com.gaofeng.common.exception.ErrorCode;
import com.gaofeng.common.ratelimit.*;
import com.gaofeng.user.domain.User;
import com.gaofeng.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthCredentialRegressionTest {
    @Mock UserService users;
    @Mock VerificationService codes;
    @Mock JwtService jwt;
    @Mock RefreshTokenStore refresh;
    @Mock LoginLogService audit;
    @Mock RedisRateLimiter limiter;
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    final ClientInfo client = new ClientInfo("127.0.0.1", "test");
    final String phone = "13800000000";
    AuthService service;

    @BeforeEach void setUp() {
        service = new AuthService(users, codes, encoder, jwt, refresh, audit,
                new AuthProperties(), limiter, new AbuseProtectionProperties());
    }

    private void tokenStub() {
        when(jwt.issueTokenPair(any())).thenReturn(new TokenPair("access", Instant.now().plusSeconds(600),
                "refresh", Instant.now().plusSeconds(3600), "jti"));
    }

    private void codeStub() {
        when(codes.verify(any(), eq(phone), eq("123456")))
                .thenReturn(new VerificationCheckResult(VerificationCodeStatus.SUCCESS, 0, 5));
    }

    @Test void registrationAndLoginPreserveTheExactPasswordIncludingSpaces() {
        codeStub(); tokenStub();
        var saved = new java.util.concurrent.atomic.AtomicReference<User>();
        when(users.createUser(any())).thenAnswer(invocation -> {
            User user = invocation.getArgument(0); user.setId(7L); saved.set(user); return user;
        });
        String password = "  Password123  ";
        service.register(new RegisterRequest(IdentifierType.PHONE, phone, "123456", password, true), client);
        assertThat(encoder.matches(password, saved.get().getPasswordHash())).isTrue();
        assertThat(encoder.matches(password.trim(), saved.get().getPasswordHash())).isFalse();
        when(users.findByPhone(phone)).thenReturn(Optional.of(saved.get()));
        assertThat(service.login(new LoginRequest(IdentifierType.PHONE, phone, null, password), client)).isNotNull();
    }

    @Test void resetPasswordCanThenBeUsedForLoginWithoutTrimming() {
        codeStub(); tokenStub();
        User user = User.builder().id(7L).phone(phone).build();
        when(users.findByPhone(phone)).thenReturn(Optional.of(user));
        String password = "  Reset12345  ";
        service.resetPassword(new PasswordResetRequest(IdentifierType.PHONE, phone, "123456", password));
        assertThat(service.login(new LoginRequest(IdentifierType.PHONE, phone, null, password), client)).isNotNull();
        verify(refresh).revokeAll(7L);
    }

    @Test void utf8ByteLimitIsCheckedBeforeConsumingVerificationCode() {
        String password = "密".repeat(24) + "A1";
        assertThatThrownBy(() -> service.register(new RegisterRequest(IdentifierType.PHONE, phone,
                "123456", password, true), client)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION));
        assertThatThrownBy(() -> service.resetPassword(new PasswordResetRequest(IdentifierType.PHONE, phone,
                "123456", password))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(codes, jwt);
        verify(users, never()).createUser(any());
    }

    @Test void overlongLoginDoesNotReachBcryptOrUserLookup() {
        assertThatThrownBy(() -> service.login(new LoginRequest(IdentifierType.PHONE, phone, null,
                "A1".repeat(40)), client)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        verifyNoInteractions(users, jwt);
    }

    @Test void accountLimitAppliesBeforeUserLookupRegardlessOfIp() {
        doThrow(new RateLimitException(60)).when(limiter).check(eq("login-account"), eq("PHONE:" + phone),
                eq(10), eq(Duration.ofMinutes(10)));
        assertThatThrownBy(() -> service.login(new LoginRequest(IdentifierType.PHONE, phone, null,
                "Password123"), client)).isInstanceOf(RateLimitException.class);
        verifyNoInteractions(users, codes, jwt);
    }

}
