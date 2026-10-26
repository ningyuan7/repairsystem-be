package com.gaofeng.repair.procurement.service;

import com.gaofeng.repair.access.domain.Role;
import com.gaofeng.repair.access.service.*;
import com.gaofeng.repair.attachment.api.dto.AttachmentDtos.Purpose;
import com.gaofeng.repair.attachment.service.AttachmentService;
import com.gaofeng.repair.common.*;
import com.gaofeng.repair.procurement.api.dto.ProcurementDtos.*;
import com.gaofeng.repair.procurement.domain.*;
import com.gaofeng.repair.procurement.mapper.ProcurementMapper;
import com.gaofeng.repair.ticket.api.dto.TicketDtos.EventPayload;
import com.gaofeng.repair.ticket.domain.*;
import com.gaofeng.repair.ticket.mapper.TicketMapper;
import com.gaofeng.repair.ticket.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProcurementService {
    private static final BigDecimal MAX_TOTAL=new BigDecimal("999999999999.99");
    private final ProcurementMapper mapper;
    private final PurchaseAccess visibility;
    private final RepairAccess access;
    private final AccessService audit;
    private final TicketAccess ticketAccess;
    private final TicketService tickets;
    private final TicketMapper ticketMapper;
    private final RepairMaterials readiness;
    private final AttachmentService attachments;
    private final com.gaofeng.repair.ocr.service.OcrSourceValidator ocrSources;

    @Transactional public OrderResult create(long actor,long ticketId,CreateOrder body) {
        ticketAccess.visible(actor,ticketId); access.hq(actor);
        Ticket ticket=tickets.lockForMutation(actor,ticketId,body.expectedTicketVersion(),List.of());
        TicketService.state(ticket,TicketStatus.WAITING_PROCUREMENT);
        if(!ticket.isProcurementRequired()) throw TicketService.transition("该工单不需要采购");
        if(mapper.active(ticketId)!=null) throw new RepairException(409,"ACTIVE_PURCHASE_EXISTS","该工单已有有效采购单");
        PurchaseOrder order=new PurchaseOrder(); order.setTicketId(ticketId); order.setStatus(PurchaseStatus.DRAFT); order.setCurrency("CNY");
        order.setAdjustmentAmount(BigDecimal.ZERO.setScale(2)); order.setConfirmedTotal(BigDecimal.ZERO.setScale(2));
        try { mapper.insertOrder(order); } catch(DuplicateKeyException ex) { throw new RepairException(409,"ACTIVE_PURCHASE_EXISTS","该工单已有有效采购单"); }
        advance(actor,ticket,"purchase-create","总部创建采购草稿",true);
        return result(order,ticket,null);
    }
    @Transactional(readOnly=true) public OrderView get(long actor,long id) { PurchaseOrder order=visibility.hq(actor,id); return view(order,ticketAccess.visible(actor,order.getTicketId())); }
    @Transactional(readOnly=true) public List<OrderView> forTicket(long actor,long ticketId) {
        Ticket ticket=ticketAccess.visible(actor,ticketId); access.hq(actor);
        return mapper.ordersForTicket(ticketId).stream().map(order->view(order,ticket)).toList();
    }
    @Transactional public OrderView draft(long actor,long id,DraftInput body) {
        Locked locked=lock(actor,id,body.expectedVersion(),body.expectedTicketVersion(),true);
        PurchaseOrder order=locked.order(); Ticket ticket=locked.ticket();
        requireState(order,PurchaseStatus.DRAFT); TicketService.state(ticket,TicketStatus.WAITING_PROCUREMENT);
        ocrSources.validate(id,body.sourceOcrJobId(),null);
        List<PurchaseItem> existing=mapper.lockItems(id); Set<Long> retained=new HashSet<>();
        BigDecimal total=new BigDecimal(body.adjustmentAmount());
        for(ItemInput input:body.items()) {
            Long itemId=input.id()==null?null:Long.parseLong(input.id());
            PurchaseItem item=itemId==null?new PurchaseItem():existing.stream().filter(row->row.getId().equals(itemId)).findFirst().orElseThrow(()->RepairException.invalid("采购明细不属于当前草稿"));
            if(itemId!=null && !retained.add(itemId)) throw RepairException.invalid("采购明细不能重复");
            String sourceJob=input.sourceOcrJobId()==null?body.sourceOcrJobId():input.sourceOcrJobId();
            ocrSources.validate(id,sourceJob,input.sourceCandidateIndex());
            if(input.specConfirmed() && (blank(input.brand()) || blank(input.model()))) throw incomplete("确认规格前请填写品牌和型号，未知时可明确填写“未标注”");
            item.setPurchaseOrderId(id); item.setPurpose(input.purpose().name()); item.setName(input.name().strip()); item.setBrand(clean(input.brand())); item.setModel(clean(input.model()));
            item.setQuantity(input.quantity()); item.setUnitPrice(decimal(input.unitPrice())); item.setSpecConfirmed(input.specConfirmed());
            item.setSpecConfirmedBy(input.specConfirmed()?actor:null); item.setSpecConfirmedAt(input.specConfirmed()?Instant.now():null);
            item.setSourceOcrJobId(input.sourceCandidateIndex()==null?null:sourceJob); item.setSourceCandidateIndex(input.sourceCandidateIndex());
            total=total.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            if(itemId==null) mapper.insertItem(item); else if(mapper.updateItem(item)!=1) throw TicketService.transition("采购明细已经发生到货，不能修改");
        }
        for(PurchaseItem old:existing) if(!retained.contains(old.getId())) mapper.deleteItem(old.getId());
        validateTotal(total);
        order.setSupplier(body.supplier().strip()); order.setOrderNumber(clean(body.orderNumber())); order.setOrderedAt(body.orderedAt()); order.setCurrency(body.currency());
        order.setAdjustmentAmount(decimal(body.adjustmentAmount())); order.setConfirmedTotal(total.setScale(2));
        update(order); advance(actor,ticket,"purchase-draft","总部保存采购草稿",true);
        audit.audit(actor,"PURCHASE_DRAFT_SAVE","PURCHASE",Long.toString(id),Map.of("confirmedTotal",money(total),"adjustmentAmount",money(order.getAdjustmentAmount()),"items",body.items().size()));
        return view(order,ticket);
    }
    @Transactional public OrderResult place(long actor,long id,VersionInput body) {
        Locked locked=lock(actor,id,body.expectedVersion(),body.expectedTicketVersion(),true);
        PurchaseOrder order=locked.order(); Ticket ticket=locked.ticket(); requireState(order,PurchaseStatus.DRAFT);
        TicketService.state(ticket,TicketStatus.WAITING_PROCUREMENT);
        List<PurchaseItem> items=mapper.lockItems(id); validatePlacement(order,ticket,items);
        order.setStatus(PurchaseStatus.ORDERED); update(order);
        TicketStatus from=ticket.getStatus(); ticket.setStatus(TicketStatus.READY_FOR_DISPATCH);
        advance(actor,ticket,from,"purchase-place","总部已记录采购下单，可安排工程师；材料收齐后才能开工",false);
        audit.audit(actor,"PURCHASE_PLACE","PURCHASE",Long.toString(id),Map.of("confirmedTotal",money(order.getConfirmedTotal()),"currency",order.getCurrency()));
        return result(order,ticket,null);
    }
    @Transactional public OrderResult cancel(long actor,long id,VersionInput body) {
        Locked locked=lock(actor,id,body.expectedVersion(),body.expectedTicketVersion(),true);
        PurchaseOrder order=locked.order(); Ticket ticket=locked.ticket();
        requireState(order,PurchaseStatus.DRAFT,PurchaseStatus.ORDERED); TicketService.state(ticket,TicketStatus.WAITING_PROCUREMENT,TicketStatus.READY_FOR_DISPATCH);
        TicketService.requireText(body.reason(),"请填写取消采购的原因");
        if(mapper.lockItems(id).stream().anyMatch(item->item.getReceivedQuantity()!=0)) throw TicketService.transition("已经发生到货，不能取消采购");
        TicketStatus from=ticket.getStatus(); order.setStatus(PurchaseStatus.CANCELLED); update(order); ticket.setStatus(TicketStatus.WAITING_PROCUREMENT);
        advance(actor,ticket,from,"purchase-cancel",body.reason(),false);
        audit.audit(actor,"PURCHASE_CANCEL","PURCHASE",Long.toString(id),Map.of("reason",body.reason()));
        return result(order,ticket,null);
    }
    @Transactional(readOnly=true) public Materials materials(long actor,long ticketId) {
        Ticket ticket=ticketAccess.visible(actor,ticketId); PurchaseOrder order=mapper.active(ticketId);
        if(order==null || order.getStatus()==PurchaseStatus.DRAFT) return new Materials(ticketId,ticket.isProcurementRequired(),null,null,ticket.getVersion(),ticket.isProcurementRequired()?"WAITING_ORDER":"NOT_REQUIRED",!ticket.isProcurementRequired(),List.of());
        return new Materials(ticketId,ticket.isProcurementRequired(),order.getId(),order.getVersion(),ticket.getVersion(),order.getStatus().name(),readiness.ready(ticket),mapper.materialItems(order.getId()));
    }
    @Transactional public OrderResult receive(long actor,long id,String requestKey,ReceiptInput body) {
        Locked locked=lock(actor,id,body.expectedVersion(),body.expectedTicketVersion(),false);
        PurchaseOrder order=locked.order(); Ticket ticket=locked.ticket(); requireState(order,PurchaseStatus.ORDERED,PurchaseStatus.PARTIALLY_RECEIVED);
        if(ticket.getStatus().terminal()) throw TicketService.transition("已结束的工单不接受新的到货记录");
        List<PurchaseItem> items=mapper.lockItems(id); Set<Long> seen=new HashSet<>();
        for(ReceiptItemInput input:body.items()) {
            long itemId=Long.parseLong(input.purchaseItemId());
            if(!seen.add(itemId)) throw RepairException.invalid("同一物品的到货数量请合并填写");
            PurchaseItem item=items.stream().filter(row->row.getId()==itemId).findFirst().orElseThrow(()->RepairException.invalid("到货物品不属于本采购单"));
            if(item.getReceivedQuantity()+input.quantity()>item.getQuantity()) throw new RepairException(409,"MATERIAL_OVER_RECEIPT","本次数量超过待收数量，请核对后重试");
        }
        Receipt receipt=new Receipt(); receipt.setPurchaseOrderId(id); receipt.setReceiverId(actor); receipt.setReceivedAt(Instant.now());
        receipt.setNote(body.note()==null?"":body.note().strip()); receipt.setRequestKey(requestKey); mapper.insertReceipt(receipt);
        for(ReceiptItemInput input:body.items()) {
            long itemId=Long.parseLong(input.purchaseItemId());
            if(mapper.receive(itemId,id,input.quantity())!=1) throw new RepairException(409,"MATERIAL_OVER_RECEIPT","到货数量已经变化，请重新核对");
            mapper.receiptLine(receipt.getId(),itemId,input.quantity());
        }
        boolean complete=mapper.materialItems(id).stream().allMatch(item->item.quantity()==item.receivedQuantity());
        order.setStatus(complete?PurchaseStatus.RECEIVED:PurchaseStatus.PARTIALLY_RECEIVED); if(complete) order.setReceivedAt(Instant.now()); update(order);
        TicketEvent event=advance(actor,ticket,"materials-received",complete?"所需材料已全部收齐":"已登记部分材料到货",false);
        mapper.linkReceiptEvent(receipt.getId(),event.getId());
        attachments.bindPurchase(actor,ticket.getStoreId(),ticket.getId(),id,event.getId(),Purpose.RECEIPT,body.attachmentIds());
        return result(order,ticket,receipt.getId());
    }
    @Transactional(readOnly=true) public List<ReceiptView> receipts(long actor,long id) {
        visibility.visible(actor,id); boolean hq=access.active(actor).getRole()==Role.HQ_ADMIN;
        return mapper.receipts(id).stream().map(row->new ReceiptView(row.getId(),row.getReceiverName(),row.getReceivedAt(),row.getNote(),mapper.receiptLines(row.getId()),hq?attachments.purchaseImages(id,"RECEIPT",row.getEventId()):List.of())).toList();
    }
    @Transactional public OrderResult bindScreenshot(long actor,String attachmentId,long orderId,VersionInput body) {
        Locked locked=lock(actor,orderId,body.expectedVersion(),body.expectedTicketVersion(),true);
        PurchaseOrder order=locked.order(); Ticket ticket=locked.ticket(); requireState(order,PurchaseStatus.DRAFT); TicketService.state(ticket,TicketStatus.WAITING_PROCUREMENT);
        if(attachments.purchaseImages(orderId,"ORDER_SCREENSHOT",null).size()>=6) throw RepairException.invalid("每个采购单最多绑定 6 张订单截图");
        update(order); TicketEvent event=advance(actor,ticket,"purchase-image","总部补充订单截图",true);
        attachments.bindPurchase(actor,ticket.getStoreId(),ticket.getId(),orderId,event.getId(),Purpose.ORDER_SCREENSHOT,List.of(attachmentId));
        return result(order,ticket,null);
    }
    @Transactional(readOnly=true) public CostView costs(long actor,long ticketId) { Ticket ticket=ticketAccess.visible(actor,ticketId); access.hq(actor); return costView(ticket); }
    @Transactional public CostView confirmCosts(long actor,long ticketId,CostInput body) {
        ticketAccess.visible(actor,ticketId); access.hq(actor);
        Ticket ticket=tickets.lockForMutation(actor,ticketId,body.expectedVersion(),List.of()); TicketService.state(ticket,TicketStatus.WAITING_ACCEPTANCE,TicketStatus.COMPLETED);
        Cost cost=mapper.lockCost(ticketId);
        if(cost==null) { if(body.expectedCostVersion()!=null) throw new RepairException(409,"VERSION_CONFLICT","费用尚未建立，请重新加载"); cost=new Cost(); cost.setTicketId(ticketId); }
        else { if(body.expectedCostVersion()==null) throw RepairException.invalid("更新费用必须提供费用版本号"); RepairException.version(body.expectedCostVersion(),cost.getVersion()); }
        cost.setLaborAmount(decimal(body.laborAmount())); cost.setOtherAmount(decimal(body.otherAmount())); cost.setNote(body.note()); cost.setConfirmedBy(actor); cost.setConfirmedAt(Instant.now());
        if(mapper.cost(ticketId)==null) mapper.insertCost(cost); else if(mapper.updateCost(cost)!=1) throw new RepairException(409,"VERSION_CONFLICT","费用已变化");
        advance(actor,ticket,"costs-confirmed","总部已确认维修费用",true);
        audit.audit(actor,"COST_CONFIRM","TICKET",Long.toString(ticketId),Map.of("laborAmount",money(cost.getLaborAmount()),"otherAmount",money(cost.getOtherAmount()),"note",body.note()));
        return costView(ticket);
    }
    public Locked lock(long actor,long id,int expectedOrderVersion,int expectedTicketVersion,boolean hq) {
        PurchaseOrder peek=hq?visibility.hq(actor,id):visibility.visible(actor,id);
        Ticket ticket=tickets.lockForMutation(actor,peek.getTicketId(),expectedTicketVersion,List.of());
        PurchaseOrder order=mapper.lockOrder(id); if(order==null) throw RepairException.missing(); RepairException.version(expectedOrderVersion,order.getVersion());
        return new Locked(ticket,order);
    }
    public record Locked(Ticket ticket,PurchaseOrder order) {}
    private TicketEvent advance(long actor,Ticket ticket,String action,String note,boolean hqOnly) { return advance(actor,ticket,ticket.getStatus(),action,note,hqOnly); }
    private TicketEvent advance(long actor,Ticket ticket,TicketStatus from,String action,String note,boolean hqOnly) {
        if(ticketMapper.update(ticket)!=1) throw new RepairException(409,"VERSION_CONFLICT","工单已变化，请刷新"); ticket.setVersion(ticket.getVersion()+1);
        return tickets.event(actor,ticket,from,action,note,EventPayload.empty(),hqOnly);
    }
    private void update(PurchaseOrder order) { if(mapper.updateOrder(order)!=1) throw new RepairException(409,"VERSION_CONFLICT","采购单已变化，请刷新"); order.setVersion(order.getVersion()+1); }
    private OrderView view(PurchaseOrder order,Ticket ticket) {
        List<ItemView> items=mapper.items(order.getId()).stream().map(item->new ItemView(item.getId(),item.getPurpose(),item.getName(),item.getBrand(),item.getModel(),item.getQuantity(),money(item.getUnitPrice()),item.getReceivedQuantity(),item.isSpecConfirmed(),item.getSpecConfirmedBy(),item.getSpecConfirmedAt(),item.getSourceOcrJobId(),item.getSourceCandidateIndex())).toList();
        return new OrderView(order.getId(),order.getTicketId(),order.getStatus().name(),order.getVersion(),ticket.getVersion(),ticket.getResolutionPlan(),order.getOrderNumber(),order.getSupplier(),order.getOrderedAt(),order.getCurrency(),money(order.getAdjustmentAmount()),money(order.getConfirmedTotal()),order.getReceivedAt(),items,attachments.purchaseImages(order.getId(),"ORDER_SCREENSHOT",null));
    }
    private CostView costView(Ticket ticket) {
        Cost cost=mapper.cost(ticket.getId()); PurchaseOrder order=mapper.active(ticket.getId());
        BigDecimal purchase=order!=null && order.getStatus()!=PurchaseStatus.DRAFT?order.getConfirmedTotal():BigDecimal.ZERO;
        if(cost==null) return new CostView(ticket.getId(),"PENDING",null,ticket.getVersion(),money(purchase),null,null,null,null,null,null);
        return new CostView(ticket.getId(),"CONFIRMED",cost.getVersion(),ticket.getVersion(),money(purchase),money(cost.getLaborAmount()),money(cost.getOtherAmount()),money(purchase.add(cost.getLaborAmount()).add(cost.getOtherAmount())),cost.getNote(),cost.getConfirmedBy(),cost.getConfirmedAt());
    }
    private void validatePlacement(PurchaseOrder order,Ticket ticket,List<PurchaseItem> items) {
        if(items.isEmpty() || blank(order.getSupplier()) || order.getOrderedAt()==null) throw incomplete("请补齐供应商、下单时间和采购明细");
        if(order.getOrderedAt().isAfter(Instant.now().plusSeconds(300))) throw incomplete("下单时间不能填写未来时间");
        for(PurchaseItem item:items) if(!item.isSpecConfirmed() || blank(item.getBrand()) || blank(item.getModel())) throw incomplete("每条明细的品牌和型号都需要人工核对确认");
        long replacements=items.stream().filter(item->"REPLACEMENT".equals(item.getPurpose())).count();
        if("REPLACEMENT".equals(ticket.getResolutionPlan())) {
            if(replacements!=1 || items.stream().anyMatch(item->"REPLACEMENT".equals(item.getPurpose()) && item.getQuantity()!=1)) throw incomplete("设备替换必须有且只有一条数量为 1 的替换设备项");
        } else if(replacements!=0) throw incomplete("配件维修不能包含替换设备项");
        BigDecimal total=items.stream().map(item->item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))).reduce(order.getAdjustmentAmount(),BigDecimal::add);
        validateTotal(total); if(total.compareTo(order.getConfirmedTotal())!=0) throw incomplete("采购合计不一致，请重新保存草稿");
    }
    public static void requireState(PurchaseOrder order,PurchaseStatus... states) { if(Arrays.stream(states).noneMatch(state->state==order.getStatus())) throw TicketService.transition("当前采购状态不允许此操作"); }
    private static void validateTotal(BigDecimal value) { if(value.signum()<0 || value.compareTo(MAX_TOTAL)>0) throw incomplete("采购合计必须为非负数且不超出金额上限"); }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
    private static String clean(String value) { return blank(value)?null:value.strip(); }
    public static BigDecimal decimal(String value) { return new BigDecimal(value).setScale(2); }
    public static String money(BigDecimal value) { return value.setScale(2).toPlainString(); }
    private static RepairException incomplete(String message) { return new RepairException(422,"PURCHASE_FIELDS_INCOMPLETE",message); }
    private static OrderResult result(PurchaseOrder order,Ticket ticket,Long receiptId) { return new OrderResult(order.getId(),order.getVersion(),ticket.getId(),ticket.getTicketNo(),ticket.getStatus().name(),ticket.getVersion(),receiptId); }
}
