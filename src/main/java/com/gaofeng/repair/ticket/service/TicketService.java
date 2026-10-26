package com.gaofeng.repair.ticket.service;

import com.gaofeng.repair.access.api.dto.AccessDtos.Page;
import com.gaofeng.repair.access.domain.*;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.repair.access.service.*;
import com.gaofeng.repair.asset.domain.Asset;
import com.gaofeng.repair.asset.mapper.AssetMapper;
import com.gaofeng.repair.asset.service.AssetService;
import com.gaofeng.repair.attachment.api.dto.AttachmentDtos.Purpose;
import com.gaofeng.repair.attachment.service.AttachmentService;
import com.gaofeng.repair.common.*;
import com.gaofeng.repair.ticket.api.dto.TicketDtos.*;
import com.gaofeng.repair.ticket.domain.*;
import com.gaofeng.repair.ticket.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TicketService {
    private final TicketMapper mapper;
    private final com.gaofeng.repair.cache.EngineerReadCache readCache;
    private final com.gaofeng.repair.cache.mapper.EngineerCacheMapper cacheDirectory;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final AssetMapper assets;
    private final AccessMapper directory;
    private final RepairAccess access;
    private final AssetService assetAccess;
    private final TicketAccess visibility;
    private final AttachmentService attachments;
    private final RepairMaterials materials;
    private final JsonSupport json;
    private final com.gaofeng.repair.notification.service.NotificationService notifications;

    @Transactional public CommandResult create(long actor,CreateTicket body) {
        Member member=access.active(actor); if(member.getRole()!=Role.STORE_MANAGER) throw RepairException.forbidden();
        long storeId=Long.parseLong(body.storeId()); assetAccess.visibleStore(member,storeId);
        Store store=directory.store(storeId); if(store==null) throw RepairException.missing();
        if(!"ACTIVE".equals(store.getStatus())) throw RepairException.invalid("门店已经停用，不能提交新的报修");
        var category=assets.category(Long.parseLong(body.categoryId()));
        if(category==null || !"ACTIVE".equals(category.getStatus())) throw RepairException.invalid("报修分类不存在或已停用");
        Asset asset=null; Snapshot snapshot;
        if(body.assetId()!=null) {
            asset=assets.lockAsset(Long.parseLong(body.assetId()));
            if(asset==null || asset.getStoreId()!=storeId) throw RepairException.missing();
            if(!"ACTIVE".equals(asset.getLifecycle())) throw RepairException.invalid("设施已经退役");
            snapshot=snapshot(store,asset);
        } else {
            requireText(body.facilityName(),"请填写设施名称"); requireText(body.zone(),"请填写设施位置");
            snapshot=new Snapshot(store.getName(),body.facilityName().strip(),body.zone().strip(),null,body.brand(),body.model(),null);
        }
        Ticket ticket=new Ticket(); ticket.setTicketNo(UUID.randomUUID().toString().replace("-","")); ticket.setStoreId(storeId); ticket.setReporterId(actor);
        ticket.setAssetId(asset==null?null:asset.getId()); ticket.setCategoryId(category.getId()); ticket.setTitle(body.title().strip());
        ticket.setDescription(body.description().strip()); ticket.setFacilitySnapshot(json.write(snapshot)); ticket.setContactName(body.contactName().strip());
        ticket.setContactPhone(body.contactPhone().strip()); ticket.setPriority(body.priority().name()); ticket.setStatus(TicketStatus.SUBMITTED); ticket.setSubmittedAt(Instant.now());
        mapper.insert(ticket); ticket.setTicketNo("WX"+ticket.getId()); mapper.setTicketNo(ticket.getId(),ticket.getTicketNo());
        if(asset!=null) { occupy(asset.getId(),ticket.getId()); assets.updateHealth(asset.getId(),"FAULTY"); }
        TicketEvent event=event(actor,ticket,null,"submit","门店提交报修",new EventPayload(1,snapshot,null,null,null,null,null),false);
        attachments.bind(actor,storeId,ticket.getId(),event.getId(),Purpose.FAULT,body.attachmentIds());
        return result(ticket);
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Page<TicketView> list(long actor,Long storeId,TicketStatus status,Priority priority,Long assignee,String keyword,Instant from,Instant to,int page,int size) {
        AccessService.checkPage(page,size); Member member=access.active(actor);
        if(from!=null && to!=null && !from.isBefore(to)) throw RepairException.invalid("结束时间必须晚于开始时间");
        String search=AssetService.search(keyword),state=status==null?null:status.name(),level=priority==null?null:priority.name();
        if(member.getRole()==Role.ENGINEER) assignee=actor;
        final Long selectedAssignee=assignee;
        java.util.function.Supplier<Page<TicketView>> load=()->{
            List<TicketView> items=mapper.list(actor,member.getRole().name(),storeId,state,level,selectedAssignee,search,from,to,(page-1)*size,size).stream().map(t->view(t,member,false)).toList();
            return new Page<>(items,page,size,mapper.count(actor,member.getRole().name(),storeId,state,level,selectedAssignee,search,from,to));
        };
        if(member.getRole()==Role.ENGINEER&&page==1&&size==20&&storeId==null&&priority==null&&search==null&&from==null&&to==null)
            return readCache.read(actor,member.getAuthVersion(),"tickets:"+(state==null?"ALL":state),
                objectMapper.getTypeFactory().constructParametricType(Page.class,TicketView.class),load,
                result->{var ids=result.items().stream().map(TicketView::id).toList();return ids.isEmpty()||cacheDirectory.owned(actor,ids)==ids.size();});
        return load.get();
    }
    @Transactional(readOnly=true) public TicketView detail(long actor,long id) { return view(visibility.visible(actor,id),access.active(actor),true); }
    @Transactional(readOnly=true) public Events events(long actor,long id,Long before,int size) {
        visibility.visible(actor,id); Member member=access.active(actor);
        if(size<1 || size>100) throw RepairException.invalid("每次最多读取 100 条过程记录");
        List<TicketEvent> found=mapper.events(id,member.getRole()==Role.HQ_ADMIN,before,size+1);
        List<EventView> items=found.stream().limit(size).map(e->{
            EventPayload payload=json.read(e.getPayload(),EventPayload.class);
            return new EventView(e.getId(),e.getTicketVersion(),e.getAction(),e.getFromStatus(),e.getToStatus(),e.getActorName(),e.getActorRole(),e.getNote(),
                    member.getRole()==Role.HQ_ADMIN?payload:payload.participantView(),e.getCreatedAt(),attachments.images(id,e.getId()));
        }).toList();
        return new Events(items,found.size()>size?items.get(items.size()-1).id():null);
    }
    @Transactional public CommandResult command(long actor,long id,String action,Command body) {
        Member member=access.active(actor);
        List<Long> additional="asset-link".equals(action) && body.assetId()!=null?List.of(Long.parseLong(body.assetId())):List.of();
        Ticket ticket=lockForMutation(actor,id,body.expectedVersion(),additional);
        TicketStatus from=ticket.getStatus(); EventPayload payload=EventPayload.empty(); String note=body.note()==null?"":body.note();
        Purpose imagePurpose=null; Instant now=Instant.now(); Long formerAssignee=ticket.getAssigneeId();
        switch(action) {
            case "asset-link" -> {
                hq(member); state(ticket,TicketStatus.SUBMITTED); if(ticket.getAssetId()!=null) throw transition("工单已经关联设施");
                if(body.assetId()==null) throw RepairException.invalid("请选择设施"); requireText(body.reason(),"请说明关联原因");
                Asset asset=assets.asset(Long.parseLong(body.assetId()));
                if(asset==null || !asset.getStoreId().equals(ticket.getStoreId())) throw RepairException.missing();
                if(!"ACTIVE".equals(asset.getLifecycle())) throw RepairException.invalid("设施已经退役");
                occupy(asset.getId(),id); ticket.setAssetId(asset.getId()); assets.updateHealth(asset.getId(),"FAULTY");
                Snapshot snapshot=snapshot(directory.store(ticket.getStoreId()),asset); ticket.setFacilitySnapshot(json.write(snapshot));
                payload=new EventPayload(1,snapshot,null,null,null,null,null); note=body.reason();
            }
            case "approve" -> {
                hq(member); state(ticket,TicketStatus.SUBMITTED);
                if(body.procurementRequired()==null || body.resolutionPlan()==null || body.priority()==null) throw RepairException.invalid("请确认处理方案、采购需求和优先级");
                if(body.procurementRequired()==(body.resolutionPlan()==ResolutionPlan.REPAIR)) throw RepairException.invalid("采购需求与处理方案不一致");
                ticket.setProcurementRequired(body.procurementRequired()); ticket.setResolutionPlan(body.resolutionPlan().name()); ticket.setPriority(body.priority().name());
                ticket.setStatus(body.procurementRequired()?TicketStatus.WAITING_PROCUREMENT:TicketStatus.READY_FOR_DISPATCH);
            }
            case "reject" -> { hq(member); state(ticket,TicketStatus.SUBMITTED); requireText(body.reason(),"请填写驳回原因"); ticket.setStatus(TicketStatus.REJECTED); note=body.reason(); }
            case "cancel" -> {
                if(member.getRole()==Role.HQ_ADMIN) { state(ticket,TicketStatus.WAITING_PROCUREMENT); materials.cancelDraft(ticket); }
                else if(member.getRole()==Role.STORE_MANAGER) state(ticket,TicketStatus.SUBMITTED);
                else throw RepairException.forbidden();
                requireText(body.reason(),"请填写撤销原因"); ticket.setStatus(TicketStatus.CANCELLED); note=body.reason();
            }
            case "assign","reassign" -> {
                hq(member); state(ticket,action.equals("assign")?TicketStatus.READY_FOR_DISPATCH:TicketStatus.IN_PROGRESS);
                if(action.equals("reassign")) { requireText(body.reason(),"请填写改派原因"); note=body.reason(); }
                if(!materials.ordered(ticket)) throw transition("需要先完成采购下单");
                if(body.engineerId()==null) throw RepairException.invalid("请选择工程师");
                Member engineer=access.active(Long.parseLong(body.engineerId()));
                if(engineer.getRole()!=Role.ENGINEER) throw RepairException.invalid("受派人员必须是有效工程师");
                if(engineer.getUserId().equals(ticket.getAssigneeId())) throw RepairException.invalid("改派时请选择其他工程师");
                ticket.setAssigneeId(engineer.getUserId()); ticket.setAssignedAt(now); ticket.setStatus(TicketStatus.ASSIGNED);
            }
            case "withdraw-assignment" -> {
                hq(member); state(ticket,TicketStatus.ASSIGNED,TicketStatus.ACCEPTED); requireText(body.reason(),"请填写撤回原因");
                ticket.setAssigneeId(null); ticket.setAssignedAt(null); ticket.setStatus(TicketStatus.READY_FOR_DISPATCH); note=body.reason();
            }
            case "accept" -> { engineer(member,ticket); state(ticket,TicketStatus.ASSIGNED); ticket.setStatus(TicketStatus.ACCEPTED); if(ticket.getAcceptedAt()==null) ticket.setAcceptedAt(now); }
            case "appointment" -> {
                engineer(member,ticket); state(ticket,TicketStatus.ACCEPTED,TicketStatus.IN_PROGRESS);
                if(body.scheduledAt()==null || body.scheduledAt().isBefore(now.minusSeconds(60))) throw RepairException.invalid("预约时间不能早于当前时间");
                payload=new EventPayload(1,null,body.scheduledAt(),null,null,null,null);
            }
            case "start" -> {
                engineer(member,ticket); state(ticket,TicketStatus.ACCEPTED); requireMaterials(ticket);
                ticket.setStatus(TicketStatus.IN_PROGRESS); if(ticket.getFirstStartedAt()==null) ticket.setFirstStartedAt(now);
                if(ticket.getAssetId()!=null) assets.updateHealth(ticket.getAssetId(),"REPAIRING");
            }
            case "work-records" -> { engineer(member,ticket); state(ticket,TicketStatus.IN_PROGRESS); requireText(body.note(),"请填写维修过程"); imagePurpose=Purpose.WORK_RECORD; }
            case "submit-result" -> {
                engineer(member,ticket); state(ticket,TicketStatus.IN_PROGRESS); requireMaterials(ticket); requireText(body.resultNote(),"请填写维修结果");
                if(body.attachmentIds()==null || body.attachmentIds().isEmpty()) throw RepairException.invalid("请上传至少一张维修结果图");
                ticket.setStatus(TicketStatus.WAITING_ACCEPTANCE); ticket.setCompletedRepairAt(now); ticket.setSubmittedForAcceptanceAt(now);
                payload=new EventPayload(1,null,null,body.resultNote(),body.replacementSerialNumber(),money(body.laborSuggestedAmount()),money(body.otherSuggestedAmount()));
                note="工程师提交维修结果"; imagePurpose=Purpose.RESULT;
            }
            case "confirm","return" -> {
                if(member.getRole()!=Role.STORE_MANAGER && member.getRole()!=Role.HQ_ADMIN) throw RepairException.forbidden();
                state(ticket,TicketStatus.WAITING_ACCEPTANCE);
                if(member.getRole()==Role.HQ_ADMIN) requireText(body.reason(),"总部代验收需要填写原因");
                if(action.equals("return")) { requireText(body.reason(),"请填写退回原因"); ticket.setStatus(TicketStatus.IN_PROGRESS); note=body.reason(); }
                else {
                    if("REPLACEMENT".equals(ticket.getResolutionPlan())) materials.replaceAsset(ticket);
                    ticket.setStatus(TicketStatus.COMPLETED); ticket.setCompletedAt(now);
                    if(ticket.getAssetId()!=null && !"REPLACEMENT".equals(ticket.getResolutionPlan())) assets.updateHealth(ticket.getAssetId(),"NORMAL");
                    if(member.getRole()==Role.HQ_ADMIN) note=body.reason();
                }
            }
            default -> throw RepairException.missing();
        }
        if(mapper.update(ticket)!=1) throw new RepairException(409,"VERSION_CONFLICT","工单已更新，请重新加载");
        ticket.setVersion(ticket.getVersion()+1);
        TicketEvent event=event(actor,ticket,from,action,note,payload,false);
        if(imagePurpose!=null) attachments.bind(actor,ticket.getStoreId(),id,event.getId(),imagePurpose,body.attachmentIds());
        if(ticket.getStatus().terminal()) mapper.releaseAsset(id);
        if(formerAssignee!=null && !formerAssignee.equals(ticket.getAssigneeId())) notifications.emit(formerAssignee,event.getId(),id,"ASSIGNMENT_REMOVED",ticket.getTicketNo()+" 的原派单已撤回");
        return result(ticket);
    }
    /** Shared phase-3 lock boundary: all known asset locks precede the ticket row lock. */
    public Ticket lockForMutation(long actor,long id,int expected,List<Long> additionalAssets) {
        Ticket peek=visibility.visible(actor,id);
        SortedSet<Long> locks=new TreeSet<>(additionalAssets); if(peek.getAssetId()!=null) locks.add(peek.getAssetId());
        for(long assetId:locks) assets.lockAsset(assetId);
        Ticket locked=mapper.lockTicket(id); if(locked==null) throw RepairException.missing();
        if(!Objects.equals(peek.getAssetId(),locked.getAssetId())) throw new RepairException(409,"VERSION_CONFLICT","设施关联已变更，请刷新");
        visibility.visible(actor,id); RepairException.version(expected,locked.getVersion()); return locked;
    }
    public TicketEvent event(long actor,Ticket ticket,TicketStatus from,String action,String note,EventPayload payload,boolean hqOnly) {
        Member member=access.active(actor); TicketEvent event=new TicketEvent(); event.setTicketId(ticket.getId()); event.setTicketVersion(ticket.getVersion());
        event.setAction(action); event.setFromStatus(from==null?null:from.name()); event.setToStatus(ticket.getStatus().name()); event.setActorId(actor);
        event.setActorRole(member.getRole().name()); event.setVisibility(hqOnly?"HQ_ONLY":"PARTICIPANTS"); event.setNote(note==null?"":note); event.setPayload(json.write(payload));
        mapper.insertEvent(event);
        for(long recipient:mapper.recipients(ticket.getStoreId(),ticket.getAssigneeId(),hqOnly)) if(recipient!=actor)
            notifications.emit(recipient,event.getId(),ticket.getId(),action,ticket.getTicketNo()+" · "+actionLabel(action));
        return event;
    }
    private TicketView view(Ticket t,Member member,boolean includeImages) {
        return new TicketView(t.getId(),t.getTicketNo(),t.getStoreId(),t.getStoreName(),t.getAssetId(),t.getReplacementAssetId(),t.getCategoryId(),t.getCategoryName(),t.getTitle(),t.getDescription(),
                json.read(t.getFacilitySnapshot(),Snapshot.class),t.getContactName(),t.getContactPhone(),t.getPriority(),t.getStatus(),t.getResolutionPlan(),t.isProcurementRequired(),t.getAssigneeId(),t.getAssigneeName(),
                t.getSubmittedAt(),t.getAssignedAt(),t.getAcceptedAt(),t.getFirstStartedAt(),t.getCompletedAt(),t.getVersion(),materials.ready(t),materials.costStatus(t.getId()),allowed(t,member),includeImages?attachments.images(t.getId(),null):List.of());
    }
    private List<String> allowed(Ticket t,Member member) {
        List<String> actions=new ArrayList<>();
        if(member.getRole()==Role.HQ_ADMIN) {
            switch(t.getStatus()) {
                case SUBMITTED -> { actions.addAll(List.of("approve","reject")); if(t.getAssetId()==null) actions.add("asset-link"); }
                case READY_FOR_DISPATCH -> actions.add("assign");
                case WAITING_PROCUREMENT -> actions.add("cancel");
                case ASSIGNED,ACCEPTED -> actions.add("withdraw-assignment");
                case IN_PROGRESS -> actions.add("reassign");
                case WAITING_ACCEPTANCE -> actions.addAll(List.of("confirm","return","costs"));
                case COMPLETED -> actions.add("costs");
                default -> { }
            }
            if(t.isProcurementRequired()) actions.add("purchase");
        } else if(member.getRole()==Role.STORE_MANAGER) {
            if(t.getStatus()==TicketStatus.SUBMITTED) actions.add("cancel");
            if(t.getStatus()==TicketStatus.WAITING_ACCEPTANCE) actions.addAll(List.of("confirm","return"));
        } else if(Long.valueOf(member.getUserId()).equals(t.getAssigneeId())) {
            switch(t.getStatus()) {
                case ASSIGNED -> actions.add("accept");
                case ACCEPTED -> { actions.add("appointment"); if(materials.ready(t)) actions.add("start"); }
                case IN_PROGRESS -> actions.addAll(List.of("appointment","work-records","submit-result"));
                default -> { }
            }
        }
        return actions;
    }
    private void occupy(long assetId,long ticketId) { try { mapper.occupyAsset(assetId,ticketId); } catch(DuplicateKeyException ex) { throw new RepairException(409,"ASSET_HAS_ACTIVE_TICKET","该设施已有未结工单，请先查看现有报修"); } }
    private void requireMaterials(Ticket t) { if(!materials.ready(t)) throw new RepairException(409,"MATERIALS_NOT_READY","所需材料尚未全部到货，暂不能开工"); }
    private static void hq(Member m) { if(m.getRole()!=Role.HQ_ADMIN) throw RepairException.forbidden(); }
    private static void engineer(Member m,Ticket t) { if(m.getRole()!=Role.ENGINEER || !m.getUserId().equals(t.getAssigneeId())) throw RepairException.forbidden(); }
    public static void state(Ticket t,TicketStatus... allowed) { if(Arrays.stream(allowed).noneMatch(s->s==t.getStatus())) throw transition("当前工单状态不允许此操作"); }
    public static RepairException transition(String message) { return new RepairException(409,"INVALID_TRANSITION",message); }
    public static void requireText(String text,String message) { if(text==null || text.isBlank()) throw RepairException.invalid(message); }
    private static Snapshot snapshot(Store s,Asset a) { return new Snapshot(s.getName(),a.getName(),a.getZone(),a.getAssetCode(),a.getBrand(),a.getModel(),a.getSerialNumber()); }
    private static String money(String input) { return input==null?null:new BigDecimal(input).setScale(2).toPlainString(); }
    public static CommandResult result(Ticket t) { return new CommandResult(t.getId(),t.getTicketNo(),t.getStatus(),t.getVersion()); }
    public static String actionLabel(String action) { return switch(action) {
        case "submit" -> "新报修待审核"; case "approve" -> "审核通过"; case "reject" -> "报修已驳回"; case "cancel" -> "报修已撤销";
        case "assign","reassign" -> "已安排工程师"; case "accept" -> "工程师已接单"; case "start" -> "维修已开始";
        case "submit-result" -> "维修完成待验收"; case "confirm" -> "验收完成"; case "return" -> "验收退回维修";
        case "appointment" -> "已更新上门时间"; case "work-records" -> "新增维修过程"; case "asset-link" -> "已关联设施";
        default -> "工单有新进展"; }; }
}
