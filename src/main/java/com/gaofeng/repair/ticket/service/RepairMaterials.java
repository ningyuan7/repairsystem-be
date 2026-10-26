package com.gaofeng.repair.ticket.service;

import com.gaofeng.repair.ticket.domain.Ticket;
import com.gaofeng.repair.ticket.api.dto.TicketDtos.*;
import com.gaofeng.repair.ticket.mapper.TicketMapper;
import com.gaofeng.repair.procurement.domain.*;
import com.gaofeng.repair.procurement.mapper.ProcurementMapper;
import com.gaofeng.repair.asset.domain.Asset;
import com.gaofeng.repair.asset.mapper.AssetMapper;
import com.gaofeng.repair.common.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RepairMaterials {
    private final ProcurementMapper purchases;
    private final AssetMapper assets;
    private final TicketMapper tickets;
    private final JsonSupport json;
    public boolean ready(Ticket ticket) {
        if(!ticket.isProcurementRequired()) return true;
        PurchaseOrder order=purchases.active(ticket.getId());
        if(order==null || order.getStatus()!=PurchaseStatus.RECEIVED) return false;
        var items=purchases.materialItems(order.getId());
        return !items.isEmpty() && items.stream().allMatch(item->item.quantity()==item.receivedQuantity());
    }
    public boolean ordered(Ticket ticket) {
        if(!ticket.isProcurementRequired()) return true;
        PurchaseOrder order=purchases.active(ticket.getId());
        return order!=null && (order.getStatus()==PurchaseStatus.ORDERED || order.getStatus()==PurchaseStatus.PARTIALLY_RECEIVED || order.getStatus()==PurchaseStatus.RECEIVED);
    }
    public String costStatus(long ticketId) { return purchases.cost(ticketId)==null?"PENDING":"CONFIRMED"; }
    public void cancelDraft(Ticket ticket) {
        PurchaseOrder peek=purchases.active(ticket.getId()); if(peek==null) return;
        PurchaseOrder order=purchases.lockOrder(peek.getId());
        if(order.getStatus()!=PurchaseStatus.DRAFT) throw TicketService.transition("已下单的工单不能直接撤销");
        order.setStatus(PurchaseStatus.CANCELLED); purchases.updateOrder(order);
    }
    /** Called only while the asset and ticket are locked in the acceptance transaction. */
    public void replaceAsset(Ticket ticket) {
        if(!ready(ticket)) throw new RepairException(409,"MATERIALS_NOT_READY","替换设备尚未全部收齐");
        PurchaseOrder order=purchases.active(ticket.getId());
        var items=purchases.items(order.getId()).stream().filter(item->"REPLACEMENT".equals(item.getPurpose())).toList();
        if(items.size()!=1 || items.get(0).getQuantity()!=1) throw TicketService.transition("替换设备明细不符合验收要求");
        PurchaseItem item=items.get(0); Snapshot snapshot=json.read(ticket.getFacilitySnapshot(),Snapshot.class);
        var result=tickets.latestResult(ticket.getId());
        String serial=result==null?null:json.read(result.getPayload(),EventPayload.class).replacementSerialNumber();
        Asset replacement=new Asset(); replacement.setStoreId(ticket.getStoreId()); replacement.setAssetCode(ticket.getTicketNo()+"-R-"+UUID.randomUUID().toString().substring(0,8));
        replacement.setName(item.getName()); replacement.setZone(snapshot.zone()); replacement.setBrand(item.getBrand()); replacement.setModel(item.getModel());
        replacement.setSerialNumber(serial); replacement.setLifecycle("ACTIVE"); replacement.setHealthStatus("NORMAL"); replacement.setSourcePurchaseItemId(item.getId());
        assets.insertAsset(replacement); ticket.setReplacementAssetId(replacement.getId());
        if(ticket.getAssetId()!=null) {
            Asset old=assets.asset(ticket.getAssetId()); old.setLifecycle("RETIRED"); old.setHealthStatus("FAULTY"); old.setReplacedByAssetId(replacement.getId());
            if(assets.updateAsset(old)!=1) throw new RepairException(409,"VERSION_CONFLICT","原设施状态已变化");
        }
    }
}
