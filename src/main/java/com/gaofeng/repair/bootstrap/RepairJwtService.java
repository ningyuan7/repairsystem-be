package com.gaofeng.repair.bootstrap;
import com.gaofeng.auth.config.AuthProperties;
import com.gaofeng.auth.token.*;
import com.gaofeng.repair.access.domain.LoginSession;
import com.gaofeng.repair.access.mapper.*;
import com.gaofeng.repair.access.service.*;
import com.gaofeng.user.domain.User;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/** Both token types bind to one revocable session; rotation never creates a new session. */
public class RepairJwtService extends JwtService {
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final AccessMapper members;
    private final SessionMapper sessions;
    private final LoginSessions validation;
    public RepairJwtService(JwtEncoder encoder,JwtDecoder decoder,AuthProperties properties,AccessMapper members,SessionMapper sessions,LoginSessions validation) {
        super(encoder,decoder,properties); this.encoder=encoder; this.properties=properties; this.members=members; this.sessions=sessions; this.validation=validation;
    }
    @Override @Transactional public TokenPair issueTokenPair(User user) {
        if(sessions.lockUser(user.getId())==null) throw LoginSessions.revoked();
        var member=members.lockMember(user.getId());
        if(member!=null && !"ACTIVE".equals(member.getStatus())) throw LoginSessions.revoked();
        LoginSession session=new LoginSession();session.setId(UUID.randomUUID().toString());session.setUserId(user.getId());
        session.setAuthVersion(member==null?-1:member.getAuthVersion());session.setExpiresAt(Instant.now().plus(properties.getJwt().getRefreshTokenTtl()));
        sessions.insertSession(session);return pair(user,session);
    }
    @Override @Transactional public TokenPair rotateTokenPair(User user,Jwt refresh) {
        if(sessions.lockUser(user.getId())==null) throw LoginSessions.revoked();
        var member=members.lockMember(user.getId());
        var session=validation.check(sessions.lockSession(refresh.getClaimAsString("sid")),refresh);
        if(member==null || !"ACTIVE".equals(member.getStatus()) || member.getAuthVersion()!=session.getAuthVersion()) throw LoginSessions.revoked();
        return pair(user,session);
    }
    private TokenPair pair(User user,LoginSession session) {
        Instant now=Instant.now(),accessExpiry=now.plus(properties.getJwt().getAccessTokenTtl()),refreshExpiry=session.getExpiresAt();
        if(accessExpiry.isAfter(refreshExpiry)) accessExpiry=refreshExpiry;
        String refreshId=UUID.randomUUID().toString();
        return new TokenPair(encode(user,now,accessExpiry,"access",UUID.randomUUID().toString(),session),accessExpiry,
                encode(user,now,refreshExpiry,"refresh",refreshId,session),refreshExpiry,refreshId);
    }
    private String encode(User user,Instant now,Instant expiry,String type,String id,LoginSession session) {
        var claims=JwtClaimsSet.builder().issuer(properties.getJwt().getIssuer()).issuedAt(now).expiresAt(expiry)
                .subject(user.getId().toString()).id(id).claim("uid",user.getId()).claim("token_type",type)
                .claim("sid",session.getId()).claim("repair_auth_version",session.getAuthVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
