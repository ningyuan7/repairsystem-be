package com.gaofeng.auth.token;

import com.gaofeng.auth.config.AuthConfiguration;
import com.gaofeng.auth.config.AuthProperties;
import com.gaofeng.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() throws java.security.NoSuchAlgorithmException {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setIssuer("test-issuer");
        // Tests use an ephemeral key pair and never depend on deployment secrets.
        var generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        properties.getJwt().setPrivateKey(pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        properties.getJwt().setPublicKey(pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        AuthConfiguration configuration = new AuthConfiguration(properties);
        JwtEncoder encoder = configuration.jwtEncoder();
        JwtDecoder decoder = configuration.jwtDecoder();
        jwtService = new JwtService(encoder, decoder, properties);
    }

    private static ByteArrayResource pem(String type, byte[] encoded) {
        String body = java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return new ByteArrayResource(("-----BEGIN " + type + "-----\n" + body
                + "\n-----END " + type + "-----\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void issueTokenPairAndDecode() {
        User user = User.builder()
                .id(123L)
                .nickname("tester")
                .build();

        TokenPair tokenPair = jwtService.issueTokenPair(user);

        assertThat(tokenPair.accessToken()).isNotBlank();
        assertThat(tokenPair.refreshToken()).isNotBlank();
        assertThat(tokenPair.refreshTokenId()).isNotBlank();

        Jwt accessJwt = jwtService.decode(tokenPair.accessToken());
        assertThat(jwtService.extractTokenType(accessJwt)).isEqualTo("access");
        assertThat(jwtService.extractUserId(accessJwt)).isEqualTo(123L);

        Jwt refreshJwt = jwtService.decode(tokenPair.refreshToken());
        assertThat(jwtService.extractTokenType(refreshJwt)).isEqualTo("refresh");
        assertThat(jwtService.extractUserId(refreshJwt)).isEqualTo(123L);
        assertThat(jwtService.extractTokenId(refreshJwt)).isEqualTo(tokenPair.refreshTokenId());
    }
}
