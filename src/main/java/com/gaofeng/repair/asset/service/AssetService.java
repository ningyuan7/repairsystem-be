package com.gaofeng.repair.asset.service;

import com.gaofeng.repair.access.api.dto.AccessDtos.Page;
import com.gaofeng.repair.access.domain.*;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.repair.access.service.*;
import com.gaofeng.repair.asset.api.dto.AssetDtos.*;
import com.gaofeng.repair.asset.domain.*;
import com.gaofeng.repair.asset.mapper.AssetMapper;
import com.gaofeng.repair.common.RepairException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AssetService {
    private final AssetMapper mapper;
    private final AccessMapper directory;
    private final RepairAccess access;
    private final AccessService audit;
    public List<CategoryView> categories(long actor,boolean includeDisabled) {
        Member member=access.active(actor);
        return mapper.categories(includeDisabled && member.getRole()==Role.HQ_ADMIN).stream().map(AssetService::categoryView).toList();
    }
    @Transactional public CategoryView saveCategory(long actor,Long id,CategoryRequest body) {
        access.hq(actor); Category category;
        if(id==null) { if(body.expectedVersion()!=null) throw RepairException.invalid("新建分类不需要版本号"); category=new Category(); }
        else { category=mapper.lockCategory(id); if(category==null) throw RepairException.missing(); requireVersion(body.expectedVersion(),category.getVersion()); }
        category.setCode(body.code()); category.setName(body.name().strip()); category.setStatus(body.status().name());
        if(id==null) mapper.insertCategory(category); else mapper.updateCategory(category);
        audit.audit(actor,"CATEGORY_SAVE","CATEGORY",category.getId().toString(),Map.of("status",category.getStatus()));
        return categoryView(mapper.category(category.getId()));
    }
    @Transactional(readOnly=true)
    public Page<AssetView> list(long actor,Long storeId,Condition condition,Lifecycle lifecycle,String keyword,int page,int size) {
        AccessService.checkPage(page,size); Member member=access.active(actor);
        if(member.getRole()==Role.ENGINEER) return new Page<>(List.of(),page,size,0);
        String search=search(keyword),health=condition==null?null:condition.name(),life=lifecycle==null?null:lifecycle.name();
        boolean hq=member.getRole()==Role.HQ_ADMIN;
        return new Page<>(mapper.list(actor,hq,storeId,health,life,search,(page-1)*size,size).stream().map(AssetService::view).toList(),page,size,mapper.count(actor,hq,storeId,health,life,search));
    }
    public AssetView get(long actor,long id) { Asset asset=required(id); visibleStore(access.active(actor),asset.getStoreId()); return view(asset); }
    @Transactional public AssetView save(long actor,Long id,AssetRequest body) {
        access.hq(actor); Asset asset;
        if(id==null) { if(body.expectedVersion()!=null) throw RepairException.invalid("新建设施不需要版本号"); asset=new Asset(); asset.setLifecycle("ACTIVE"); asset.setHealthStatus("NORMAL"); }
        else { asset=mapper.lockAsset(id); if(asset==null) throw RepairException.missing(); requireVersion(body.expectedVersion(),asset.getVersion()); if(!"ACTIVE".equals(asset.getLifecycle())) throw RepairException.invalid("已退役设施不能修改"); }
        long storeId=Long.parseLong(body.storeId());
        if(id!=null && asset.getStoreId()!=storeId && mapper.activeTicket(id)!=null) throw new RepairException(409,"ASSET_HAS_ACTIVE_TICKET","设施仍有未结工单，不能迁移门店");
        Store store=directory.store(storeId); if(store==null) throw RepairException.missing();
        if(!"ACTIVE".equals(store.getStatus())) throw RepairException.invalid("门店已停用，不能新增或迁入设施");
        asset.setStoreId(storeId); asset.setAssetCode(body.assetCode().strip()); asset.setName(body.name().strip()); asset.setZone(body.zone().strip());
        asset.setBrand(clean(body.brand())); asset.setModel(clean(body.model())); asset.setSerialNumber(clean(body.serialNumber()));
        if(id==null) mapper.insertAsset(asset); else mapper.updateAsset(asset);
        audit.audit(actor,"ASSET_SAVE","ASSET",asset.getId().toString(),Map.of("storeId",storeId));
        return view(mapper.asset(asset.getId()));
    }
    @Transactional public AssetView condition(long actor,long id,ConditionRequest body) {
        access.hq(actor); Asset asset=lockedWithoutTicket(id,body.expectedVersion());
        if(body.condition()==Condition.REPAIRING) throw RepairException.invalid("维修中状态由工单开工自动维护");
        asset.setHealthStatus(body.condition().name()); mapper.updateAsset(asset);
        audit.audit(actor,"ASSET_CONDITION","ASSET",Long.toString(id),Map.of("condition",body.condition(),"reason",body.reason())); return view(mapper.asset(id));
    }
    @Transactional public AssetView retire(long actor,long id,RetireRequest body) {
        access.hq(actor); Asset asset=lockedWithoutTicket(id,body.expectedVersion()); asset.setLifecycle("RETIRED"); mapper.updateAsset(asset);
        audit.audit(actor,"ASSET_RETIRE","ASSET",Long.toString(id),Map.of("reason",body.reason())); return view(mapper.asset(id));
    }
    public void visibleStore(Member member,long storeId) {
        if(member.getRole()==Role.HQ_ADMIN) return;
        if(member.getRole()!=Role.STORE_MANAGER || directory.stores(member.getUserId(),false).stream().noneMatch(s->s.getId()==storeId)) throw RepairException.missing();
    }
    private Asset lockedWithoutTicket(long id,int expected) {
        Asset asset=mapper.lockAsset(id); if(asset==null) throw RepairException.missing(); RepairException.version(expected,asset.getVersion());
        if(mapper.activeTicket(id)!=null) throw new RepairException(409,"ASSET_HAS_ACTIVE_TICKET","请先处理设施的未结工单");
        if(!"ACTIVE".equals(asset.getLifecycle())) throw RepairException.invalid("设施已经退役"); return asset;
    }
    private Asset required(long id) { Asset asset=mapper.asset(id); if(asset==null) throw RepairException.missing(); return asset; }
    private static void requireVersion(Integer expected,int actual) { if(expected==null) throw RepairException.invalid("修改需要版本号"); RepairException.version(expected,actual); }
    public static String search(String value) { if(value==null || value.isBlank()) return null; if(value.length()>100) throw RepairException.invalid("关键词最多 100 字"); return "%"+value.strip().replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
    private static String clean(String value) { return value==null || value.isBlank()?null:value.strip(); }
    public static AssetView view(Asset a) { return new AssetView(a.getId(),a.getStoreId(),a.getAssetCode(),a.getName(),a.getZone(),a.getBrand(),a.getModel(),a.getSerialNumber(),a.getLifecycle(),a.getHealthStatus(),a.getReplacedByAssetId(),a.getVersion()); }
    private static CategoryView categoryView(Category c) { return new CategoryView(c.getId(),c.getCode(),c.getName(),c.getStatus(),c.getVersion()); }
}
