package com.gaofeng.repair.access.service;
import com.gaofeng.repair.access.domain.LoginSession;
import com.gaofeng.repair.access.mapper.SessionMapper;
import com.gaofeng.repair.common.RepairException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;
@Service @RequiredArgsConstructor
public class LoginSessions {
    private final SessionMapper mapper;
    public LoginSession authenticated(Jwt jwt) {
        String sid=jwt.getClaimAsString("sid");
        if(sid==null || !sid.matches("[a-f0-9-]{36}")) throw revoked();
        return check(mapper.session(sid),jwt);
    }
    public LoginSession check(LoginSession session,Jwt jwt) {
        Object generation=jwt.getClaims().get("repair_auth_version");
        if(session==null || session.getUserId()!=RepairAccess.userId(jwt) || session.getRevokedAt()!=null
            || !session.getExpiresAt().isAfter(Instant.now()) || !(generation instanceof Number n) || n.intValue()!=session.getAuthVersion()) throw revoked();
        return session;
    }
    public static RepairException revoked(){return new RepairException(401,"SESSION_REVOKED","登录已失效，请重新登录");}
    @Scheduled(fixedDelay=600000,initialDelay=600000)
    public void purge(){mapper.purgeExpired();}
}
