package com.gaofeng.repair.access.service;

import com.gaofeng.repair.access.api.dto.AccessDtos.*;
import com.gaofeng.repair.access.domain.*;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.repair.common.*;
import com.gaofeng.user.domain.User;
import com.gaofeng.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class InvitationService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final AccessMapper mapper;
    private final RepairAccess access;
    private final AccessService members;
    private final UserService users;
    private final PasswordEncoder passwords;
    private final JsonSupport json;
    @Transactional
    public InvitationCreated create(long actor,InvitationRequest request) {
        mapper.governanceLock();
        access.hq(actor);
        if(request.role()==Role.HQ_ADMIN) throw RepairException.invalid("邀请仅支持门店管理员或工程师；总部权限由已有总部管理员单独授权");
        String email=request.email().strip().toLowerCase(Locale.ROOT);
        users.findByEmail(email).ifPresent(user->{ if(mapper.member(user.getId())!=null) throw new RepairException(409,"ALREADY_MEMBER","该邮箱已经是系统成员"); });
        List<Long> stores=members.validateStores(request.role(),request.storeIds());
        byte[] random=new byte[32]; RANDOM.nextBytes(random);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Invitation invitation=new Invitation();
        invitation.setId(UUID.randomUUID().toString()); invitation.setEmail(email); invitation.setRole(request.role());
        invitation.setStoreIds(json.write(stores.stream().map(Object::toString).toList()));
        invitation.setTokenHash(JsonSupport.sha256(token)); invitation.setExpiresAt(Instant.now().plus(Duration.ofHours(72))); invitation.setInviterId(actor);
        mapper.insertInvitation(invitation);
        members.audit(actor,"INVITATION_CREATE","INVITATION",invitation.getId(),Map.of("role",request.role(),"storeIds",request.storeIds()));
        return new InvitationCreated(invitation.getId(),"/activate#token="+token,invitation.getExpiresAt());
    }
    @Transactional(readOnly=true)
    public InvitationPreview preview(String token) {
        Invitation invitation=valid(mapper.invitation(hashToken(token)));
        return new InvitationPreview(invitation.getEmail(),invitation.getRole(),invitation.getExpiresAt(),users.findByEmail(invitation.getEmail()).isPresent());
    }
    @Transactional
    public ActivationResult accept(AcceptInvitation request,Jwt principal) {
        mapper.governanceLock();
        Invitation invitation=valid(mapper.lockInvitation(hashToken(request.token())));
        access.hq(invitation.getInviterId());
        User user=users.findByEmail(invitation.getEmail()).orElse(null);
        if(user!=null) {
            if(principal==null || !"access".equals(principal.getClaimAsString("token_type")) || RepairAccess.userId(principal)!=user.getId())
                throw new RepairException(401,"INVITATION_LOGIN_REQUIRED","此邮箱已有账号，请先登录该账号后接受邀请");
            if(request.password()!=null && !request.password().isEmpty()) throw RepairException.invalid("已有账号请使用原密码，邀请不能重置密码");
            if(mapper.member(user.getId())!=null) throw new RepairException(409,"ALREADY_MEMBER","账号已经加入系统");
        } else {
            validatePassword(request.password());
            user=users.createUser(User.builder().email(invitation.getEmail()).nickname(request.displayName().strip())
                    .passwordHash(passwords.encode(request.password())).avatar("").tagsJson("[]").build());
        }
        Member member=new Member(); member.setUserId(user.getId()); member.setRole(invitation.getRole()); member.setStatus("ACTIVE");
        member.setDisplayName(request.displayName().strip()); member.setContactPhone("");
        List<String> stores=Arrays.asList(json.read(invitation.getStoreIds(),String[].class));
        List<Long> checked=members.validateStores(member.getRole(),stores);
        mapper.insertMember(member);
        for(long store:checked) mapper.addStore(user.getId(),store);
        mapper.consumeInvitation(invitation.getId());
        members.audit(user.getId(),"INVITATION_ACCEPT","INVITATION",invitation.getId(),Map.of("role",member.getRole()));
        return new ActivationResult(user.getId(),"账号已激活，请登录");
    }
    public void authorizeInvitedLogin(String token,String email) {
        Invitation invitation=valid(mapper.invitation(hashToken(token)));
        if(!invitation.getEmail().equals(email)) throw new RepairException(401,"INVALID_CREDENTIALS","登录凭证错误");
    }
    private Invitation valid(Invitation invitation) {
        if(invitation==null || invitation.getConsumedAt()!=null || !invitation.getExpiresAt().isAfter(Instant.now()))
            throw new RepairException(410,"INVITATION_INVALID","邀请已失效或已被使用，请联系总部重新邀请");
        return invitation;
    }
    private static String hashToken(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw new RepairException(410,"INVITATION_INVALID","邀请无效");
        return JsonSupport.sha256(token);
    }
    public static void validatePassword(String password) {
        if(password==null || password.strip().length()<8 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72
                || password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit))
            throw RepairException.invalid("密码至少 8 位，包含字母和数字，UTF-8 长度不超过 72 字节");
    }
}
