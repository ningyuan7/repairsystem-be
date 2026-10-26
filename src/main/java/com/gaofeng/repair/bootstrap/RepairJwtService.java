package com.gaofeng.repair.bootstrap;
import com.gaofeng.auth.config.AuthProperties;
import com.gaofeng.auth.token.*;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.user.domain.User;
import org.springframework.security.oauth2.jwt.*;
import java.time.Instant;
import java.util.UUID;

/** Reuses shared validation/service contract, adds a DB-backed session revocation generation. */
public class RepairJwtService extends JwtService {
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final AccessMapper members;
    public RepairJwtService(JwtEncoder encoder,JwtDecoder decoder,AuthProperties properties,AccessMapper members) {
        super(encoder,decoder,properties); this.encoder=encoder; this.properties=properties; this.members=members;
    }
    @Override public TokenPair issueTokenPair(User user) {
        var member=members.member(user.getId());
        int generation=member==null?-1:member.getAuthVersion();
        Instant now=Instant.now(),accessExpiry=now.plus(properties.getJwt().getAccessTokenTtl()),refreshExpiry=now.plus(properties.getJwt().getRefreshTokenTtl());
        String refreshId=UUID.randomUUID().toString();
        return new TokenPair(encode(user,now,accessExpiry,"access",UUID.randomUUID().toString(),generation),accessExpiry,
                encode(user,now,refreshExpiry,"refresh",refreshId,generation),refreshExpiry,refreshId);
    }
    private String encode(User user,Instant now,Instant expiry,String type,String id,int generation) {
        var claims=JwtClaimsSet.builder().issuer(properties.getJwt().getIssuer()).issuedAt(now).expiresAt(expiry)
                .subject(user.getId().toString()).id(id).claim("uid",user.getId()).claim("token_type",type)
                .claim("repair_auth_version",generation).build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
