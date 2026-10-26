package com.gaofeng.repair.access.api;

import com.gaofeng.auth.api.dto.*;
import com.gaofeng.auth.model.*;
import com.gaofeng.auth.service.AuthService;
import com.gaofeng.auth.token.JwtService;
import com.gaofeng.auth.verification.VerificationScene;
import com.gaofeng.user.service.UserService;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.repair.access.service.*;
import com.gaofeng.repair.common.RepairException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class RepairAuthController {
    private final AuthService auth;
    private final UserService users;
    private final AccessMapper mapper;
    private final RepairAccess access;
    private final InvitationService invitations;
    private final JwtService jwtService;
    public record Identity(Long id,String email,String displayName) {}
    public record LoginResult(Identity user,TokenResponse token) {}
    @PostMapping("/register") public void register() { throw new RepairException(403,"REGISTRATION_DISABLED","内部系统不开放注册，请联系总部邀请"); }
    @PostMapping("/login") public LoginResult login(@Valid @RequestBody LoginRequest request,@RequestHeader(value="X-Repair-Invitation",required=false) String invitation,HttpServletRequest http) {
        requireMember(request.identifierType(),request.identifier(),invitation);
        AuthResponse response=auth.login(request,new ClientInfo(http.getRemoteAddr(),http.getHeader("User-Agent")));
        var member=mapper.member(response.user().id());
        return new LoginResult(new Identity(response.user().id(),response.user().email(),member==null?response.user().nickname():member.getDisplayName()),response.token());
    }
    @PostMapping("/token/refresh") public TokenResponse refresh(@Valid @RequestBody TokenRefreshRequest request) {
        Jwt jwt;
        try { jwt=jwtService.decode(request.refreshToken()); }
        catch(JwtException ex) { throw new RepairException(401,"REFRESH_TOKEN_INVALID","刷新令牌无效"); }
        if(!"refresh".equals(jwt.getClaimAsString("token_type"))) throw new RepairException(401,"REFRESH_TOKEN_INVALID","刷新令牌无效");
        access.authenticated(jwt);
        return auth.refresh(request);
    }
    @PostMapping("/logout") public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) { auth.logout(request.refreshToken()); return ResponseEntity.noContent().build(); }
    @PostMapping("/send-code") public SendCodeResponse code(@Valid @RequestBody SendCodeRequest request) {
        if(request.scene()==VerificationScene.REGISTER) throw new RepairException(403,"REGISTRATION_DISABLED","不开放注册验证码");
        if(request.scene()!=VerificationScene.RESET_PASSWORD) throw new RepairException(403,"VERIFICATION_SCENE_DISABLED","邮件验证码仅用于注册验证或找回密码");
        requireMember(request.identifierType(),request.identifier(),null); return auth.sendCode(request);
    }
    @PostMapping("/password/reset") public ResponseEntity<Void> reset(@Valid @RequestBody PasswordResetRequest request) {
        requireMember(request.identifierType(),request.identifier(),null); auth.resetPassword(request); return ResponseEntity.noContent().build();
    }
    @GetMapping("/me") public Identity me(@AuthenticationPrincipal Jwt jwt) { var member=access.authenticated(jwt); return new Identity(member.getUserId(),member.getEmail(),member.getDisplayName()); }
    private void requireMember(IdentifierType type,String identifier,String invitation) {
        String normalized=type==IdentifierType.EMAIL?identifier.strip().toLowerCase(Locale.ROOT):identifier.strip();
        var user=(type==IdentifierType.EMAIL?users.findByEmail(normalized):users.findByPhone(normalized)).orElseThrow(()->new RepairException(401,"INVALID_CREDENTIALS","登录凭证错误"));
        var member=mapper.member(user.getId());
        if(member==null && invitation!=null && type==IdentifierType.EMAIL) { invitations.authorizeInvitedLogin(invitation,normalized); return; }
        if(member==null) throw new RepairException(401,"INVALID_CREDENTIALS","登录凭证错误");
        access.active(user.getId());
    }
}
