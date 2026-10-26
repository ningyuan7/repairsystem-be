package com.gaofeng.repair.access.service;

import com.gaofeng.repair.access.api.dto.AccessDtos.*;
import com.gaofeng.repair.access.domain.*;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.repair.common.*;
import com.gaofeng.auth.token.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class AccessService {
    private final AccessMapper mapper;
    private final RepairAccess access;
    private final JsonSupport json;
    private final RefreshTokenStore refreshTokens;
    @Transactional(readOnly=true)
    public Session session(long userId) {
        Member member=access.active(userId);
        List<String> permissions=switch(member.getRole()) {
            case HQ_ADMIN -> List.of("stores:manage","members:manage","assets:manage","categories:manage","tickets:review","tickets:dispatch","tickets:acceptance","purchases:manage","costs:confirm","ocr:use");
            case STORE_MANAGER -> List.of("stores:read","assets:read","tickets:create","tickets:acceptance");
            case ENGINEER -> List.of("tickets:assigned","tickets:work");
        };
        return new Session(member.getUserId(),member.getDisplayName(),member.getEmail(),member.getContactPhone(),member.getRole(),member.getVersion(),
                visibleStores(member),!member.getDisplayName().isBlank() && member.getEmail()!=null && !member.getEmail().isBlank(),permissions);
    }
    public List<StoreView> visibleStores(Member member) {
        if(member.getRole()==Role.ENGINEER) return List.of();
        return mapper.stores(member.getUserId(),member.getRole()==Role.HQ_ADMIN).stream().map(AccessService::storeView).toList();
    }
    @Transactional
    public Session profile(long actor,ProfileRequest request) {
        Member member=mapper.lockMember(actor);
        access.active(actor);
        RepairException.version(request.expectedVersion(),member.getVersion());
        member.setDisplayName(request.displayName().strip()); member.setContactPhone(request.contactPhone().strip());
        mapper.updateProfile(member);
        audit(actor,"PROFILE_UPDATE","MEMBER",Long.toString(actor),Map.of("fields",List.of("displayName","contactPhone")));
        return session(actor);
    }
    @Transactional(readOnly=true)
    public Page<MemberView> members(long actor,int page,int size) {
        access.hq(actor); checkPage(page,size);
        return new Page<>(mapper.members((page-1)*size,size).stream().map(this::memberView).toList(),page,size,mapper.countMembers());
    }
    @Transactional
    public MemberView changeAccess(long actor,long target,AccessRequest request) {
        // Governance lock must precede all member locks, including changes to non-HQ roles.
        mapper.governanceLock(); access.hq(actor);
        Member member=requiredLockedMember(target);
        RepairException.version(request.expectedVersion(),member.getVersion());
        List<Long> storeIds=validateStores(request.role(),request.storeIds());
        protectLastHq(member,request.role(),member.getStatus());
        member.setRole(request.role());
        mapper.updateMember(member); mapper.removeStores(target);
        for(Long storeId:storeIds) mapper.addStore(target,storeId);
        revokeAfterCommit(target);
        audit(actor,"MEMBER_ACCESS_UPDATE","MEMBER",Long.toString(target),Map.of("role",request.role(),"storeIds",request.storeIds(),"reason",request.reason()));
        return memberView(mapper.member(target));
    }
    @Transactional
    public MemberView changeStatus(long actor,long target,StatusRequest request,boolean enabled) {
        mapper.governanceLock(); access.hq(actor);
        Member member=requiredLockedMember(target);
        RepairException.version(request.expectedVersion(),member.getVersion());
        String status=enabled?"ACTIVE":"DISABLED";
        protectLastHq(member,member.getRole(),status); member.setStatus(status);
        mapper.updateMember(member); revokeAfterCommit(target);
        audit(actor,enabled?"MEMBER_ENABLE":"MEMBER_DISABLE","MEMBER",Long.toString(target),Map.of("reason",request.reason()));
        return memberView(mapper.member(target));
    }
    public List<Long> validateStores(Role role,List<String> requested) {
        List<Long> ids=requested.stream().map(Long::parseLong).distinct().sorted().toList();
        if(ids.size()!=requested.size()) throw RepairException.invalid("门店不能重复");
        if(role==Role.STORE_MANAGER && ids.isEmpty()) throw RepairException.invalid("门店管理员至少负责一家门店");
        if(role!=Role.STORE_MANAGER && !ids.isEmpty()) throw RepairException.invalid("此角色不应设置门店范围");
        for(long id:ids) {
            Store store=mapper.lockStore(id);
            if(store==null) throw RepairException.missing();
            if(!"ACTIVE".equals(store.getStatus())) throw RepairException.invalid("不能授权已停用的门店");
        }
        return ids;
    }
    private void protectLastHq(Member before,Role afterRole,String afterStatus) {
        if(before.getRole()==Role.HQ_ADMIN && "ACTIVE".equals(before.getStatus())
                && (afterRole!=Role.HQ_ADMIN || !"ACTIVE".equals(afterStatus)) && mapper.activeHqCount()<=1)
            throw new RepairException(409,"LAST_HQ_ADMIN","必须保留至少一个有效的总部管理员");
    }
    private void revokeAfterCommit(long userId) {
        // auth_version already revokes both token types atomically; Redis cleanup is best effort after commit.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { refreshTokens.revokeAll(userId); }
                catch(RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(AccessService.class).warn("Refresh cleanup deferred userId={} category={}",userId,ex.getClass().getSimpleName()); }
            }
        });
    }
    private Member requiredLockedMember(long id) { Member member=mapper.lockMember(id); if(member==null) throw RepairException.missing(); return member; }
    private MemberView memberView(Member member) { return new MemberView(member.getUserId(),member.getEmail(),member.getDisplayName(),member.getContactPhone(),member.getRole(),member.getStatus(),member.getVersion(),visibleStores(member)); }
    public static StoreView storeView(Store s) { return new StoreView(s.getId(),s.getCode(),s.getName(),s.getRegion(),s.getAddress(),s.getStatus(),s.getVersion()); }
    public void audit(long actor,String action,String type,String id,Object summary) { mapper.audit(actor,action,type,id,RequestIds.current(),json.write(summary)); }
    public static void checkPage(int page,int size) { if(page<1 || page>1000000 || size<1 || size>100) throw RepairException.invalid("分页范围无效"); }
}
