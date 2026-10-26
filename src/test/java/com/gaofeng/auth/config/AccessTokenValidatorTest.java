package com.gaofeng.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenValidatorTest {
    @Test void refreshTokenCannotAuthenticateBusinessRequests() {
        Jwt refresh=Jwt.withTokenValue("test").header("alg","RS256").claim("token_type","refresh").build();
        assertThat(new AccessTokenValidator().validate(refresh).hasErrors()).isTrue();
    }
    @Test void accessTokenIsAccepted() {
        Jwt access=Jwt.withTokenValue("test").header("alg","RS256").claim("token_type","access").build();
        assertThat(new AccessTokenValidator().validate(access).hasErrors()).isFalse();
    }
}

