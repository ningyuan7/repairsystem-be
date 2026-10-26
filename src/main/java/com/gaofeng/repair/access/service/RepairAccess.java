package com.gaofeng.repair.access.service;

import com.gaofeng.repair.access.domain.*;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.repair.common.RepairException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RepairAccess {
    private final AccessMapper mapper;
    public Member active(long id) {
        Member member=mapper.member(id);
        if(member==null) throw new RepairException(403,"MEMBERSHIP_REQUIRED","请先通过内部邀请加入系统");
        if(!"ACTIVE".equals(member.getStatus())) throw new RepairException(403,"MEMBER_DISABLED","账号已停用，请联系总部管理员");
        return member;
    }
    public Member authenticated(Jwt jwt) {
        if(jwt==null) throw new RepairException(401,"UNAUTHORIZED","请先登录");
        long id=userId(jwt);
        Member member=active(id);
        Object epoch=jwt.getClaims().get("repair_auth_version");
        if(!(epoch instanceof Number n) || n.intValue()!=member.getAuthVersion())
            throw new RepairException(401,"SESSION_REVOKED","账号权限已变更，请重新登录");
        return member;
    }
    public Member hq(long id) {
        Member member=active(id);
        if(member.getRole()!=Role.HQ_ADMIN) throw RepairException.forbidden();
        return member;
    }
    public static long userId(Jwt jwt) {
        Object id=jwt.getClaims().get("uid");
        if(!(id instanceof Number n) || n.longValue()<=0) throw new RepairException(401,"UNAUTHORIZED","令牌身份无效");
        return n.longValue();
    }
}
