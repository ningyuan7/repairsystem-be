package com.gaofeng.repair.access.service;
import com.gaofeng.repair.access.api.dto.AccessDtos.*;
import com.gaofeng.repair.access.domain.Store;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.repair.common.RepairException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
@Service
@RequiredArgsConstructor
public class StoreService {
    private final AccessMapper mapper;
    private final RepairAccess access;
    private final AccessService members;
    @Transactional(readOnly=true)
    public Page<StoreView> list(long actor,int page,int size) {
        AccessService.checkPage(page,size);
        var visible=members.visibleStores(access.active(actor));
        return new Page<>(visible.stream().skip((long)(page-1)*size).limit(size).toList(),page,size,visible.size());
    }
    @Transactional
    public StoreView save(long actor,Long id,StoreRequest request) {
        access.hq(actor);
        Store store;
        if(id==null) { store=new Store(); if(request.expectedVersion()!=null) throw RepairException.invalid("新建门店不需要版本号"); }
        else {
            store=mapper.lockStore(id); if(store==null) throw RepairException.missing();
            if(request.expectedVersion()==null) throw RepairException.invalid("修改门店需要版本号");
            RepairException.version(request.expectedVersion(),store.getVersion());
        }
        store.setCode(request.code().strip()); store.setName(request.name().strip()); store.setRegion(request.region().strip());
        store.setAddress(request.address().strip()); store.setStatus(request.status().name());
        if(id==null) mapper.insertStore(store); else mapper.updateStore(store);
        members.audit(actor,id==null?"STORE_CREATE":"STORE_UPDATE","STORE",store.getId().toString(),Map.of("code",store.getCode(),"status",store.getStatus()));
        return AccessService.storeView(mapper.store(store.getId()));
    }
}
