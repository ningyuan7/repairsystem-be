package com.gaofeng.repair.access.service;
import com.gaofeng.auth.api.dto.*;
import com.gaofeng.auth.model.ClientInfo;
import com.gaofeng.auth.service.AuthService;
import com.gaofeng.auth.token.RefreshTokenStore;
import com.gaofeng.repair.access.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.security.oauth2.jwt.Jwt;
@Service @RequiredArgsConstructor
public class RepairAuthFlow {
    private final AuthService auth;
    private final SessionMapper sessions;
    private final AccessMapper members;
    private final RefreshTokenStore refresh;
    @Transactional public AuthResponse login(long userId,LoginRequest request,ClientInfo client) {
        if(sessions.lockUser(userId)==null) throw LoginSessions.revoked();
        return auth.login(request,client);
    }
    @Transactional public void logout(Jwt token) {
        long id=RepairAccess.userId(token);
        sessions.lockUser(id);
        sessions.revoke(token.getClaimAsString("sid"),id);
        String jti=token.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){try{refresh.revokeToken(id,jti);}catch(RuntimeException ignored){/* DB session is already revoked. */}}
        });
    }
    @Transactional public void reset(long userId,PasswordResetRequest request) {
        if(sessions.lockUser(userId)==null) throw LoginSessions.revoked();
        members.lockMember(userId);
        auth.resetPassword(request);
        sessions.bumpAuthVersion(userId);
        sessions.revokeAll(userId);
    }
}
