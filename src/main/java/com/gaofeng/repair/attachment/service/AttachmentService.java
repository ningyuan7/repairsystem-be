package com.gaofeng.repair.attachment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaofeng.repair.access.domain.*;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.asset.service.AssetService;
import com.gaofeng.repair.attachment.api.dto.AttachmentDtos.*;
import com.gaofeng.repair.attachment.domain.Attachment;
import com.gaofeng.repair.attachment.mapper.AttachmentMapper;
import com.gaofeng.repair.common.*;
import com.gaofeng.repair.ticket.domain.*;
import com.gaofeng.repair.ticket.service.TicketAccess;
import com.gaofeng.repair.procurement.domain.PurchaseStatus;
import com.gaofeng.repair.procurement.mapper.ProcurementMapper;
import com.gaofeng.storage.ImageSanitizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class AttachmentService {
    private final AttachmentMapper mapper;
    private final AccessMapper directory;
    private final RepairAccess access;
    private final AssetService assets;
    private final TicketAccess tickets;
    private final IdempotencyService idempotency;
    private final ProcurementMapper purchases;
    private final Path root;
    private final ImageSanitizer sanitizer=new ImageSanitizer();
    public AttachmentService(AttachmentMapper mapper,AccessMapper directory,RepairAccess access,AssetService assets,TicketAccess tickets,
                             IdempotencyService idempotency,ProcurementMapper purchases,@Value("${repair.storage.local-root:./data/repair-private}") String root) {
        this.mapper=mapper; this.directory=directory; this.access=access; this.assets=assets; this.tickets=tickets; this.idempotency=idempotency; this.purchases=purchases;
        try { Path path=Path.of(root).toAbsolutePath().normalize(); Files.createDirectories(path); this.root=path.toRealPath(); }
        catch(java.io.IOException ex) { throw new IllegalStateException("Cannot initialize private repair storage",ex); }
    }
    public ResponseEntity<JsonNode> upload(long actor,String key,Purpose purpose,Long storeId,Long ticketId,Long purchaseOrderId,byte[] bytes,String type) {
        Member member=access.active(actor);
        if(purpose==Purpose.FAULT) {
            if(member.getRole()!=Role.STORE_MANAGER) throw RepairException.forbidden();
            if(storeId==null || ticketId!=null || purchaseOrderId!=null) throw RepairException.invalid("故障图需要门店作用域");
            assets.visibleStore(member,storeId);
            Store store=directory.store(storeId);
            if(store==null || !"ACTIVE".equals(store.getStatus())) throw RepairException.invalid("门店不存在或已经停用");
        } else if(purpose==Purpose.WORK_RECORD || purpose==Purpose.RESULT) {
            if(ticketId==null || purchaseOrderId!=null) throw RepairException.invalid("维修图片需要工单作用域");
            Ticket ticket=tickets.visible(actor,ticketId);
            if(member.getRole()!=Role.ENGINEER || !Long.valueOf(actor).equals(ticket.getAssigneeId())) throw RepairException.forbidden();
            if(ticket.getStatus()!=TicketStatus.IN_PROGRESS) throw new RepairException(409,"INVALID_TRANSITION","仅维修中的工单可上传过程和结果图");
            if(storeId!=null && !storeId.equals(ticket.getStoreId())) throw RepairException.missing();
            storeId=ticket.getStoreId();
        } else if(purpose==Purpose.ORDER_SCREENSHOT || purpose==Purpose.RECEIPT) {
            if(purchaseOrderId==null) throw RepairException.invalid("采购附件需要明确的采购单");
            var order=purchases.order(purchaseOrderId); if(order==null) throw RepairException.missing();
            Ticket ticket=tickets.visible(actor,order.getTicketId());
            if(ticketId!=null && !ticketId.equals(ticket.getId()) || storeId!=null && !storeId.equals(ticket.getStoreId())) throw RepairException.missing();
            ticketId=ticket.getId(); storeId=ticket.getStoreId();
            if(purpose==Purpose.ORDER_SCREENSHOT) {
                if(member.getRole()!=Role.HQ_ADMIN) throw RepairException.forbidden();
                if(order.getStatus()!=PurchaseStatus.DRAFT) throw new RepairException(409,"INVALID_TRANSITION","仅采购草稿可上传订单截图");
            } else if(order.getStatus()!=PurchaseStatus.ORDERED && order.getStatus()!=PurchaseStatus.PARTIALLY_RECEIVED)
                throw new RepairException(409,"INVALID_TRANSITION","当前采购状态不接受新的收货凭证");
        } else if(purpose==Purpose.AVATAR) {
            if(storeId!=null || ticketId!=null || purchaseOrderId!=null) throw RepairException.invalid("头像只能用于当前账号");
        } else throw RepairException.invalid("不支持的附件用途");
        if(bytes.length>ImageSanitizer.MAX_BYTES) throw new RepairException(413,"FILE_TOO_LARGE","图片不能超过 5 MiB");
        if(!"image/jpeg".equals(type) && !"image/png".equals(type)) throw RepairException.invalid("仅支持 JPG、PNG 图片");
        ImageSanitizer.Image image=sanitizer.sanitize(bytes,type);
        Attachment attachment=new Attachment(); attachment.setId(UUID.randomUUID().toString()); attachment.setUploaderId(actor);
        attachment.setStoreId(storeId); attachment.setTicketId(ticketId); attachment.setPurchaseOrderId(purchaseOrderId);
        attachment.setPurpose(purpose.name()); attachment.setStorageBackend("LOCAL"); attachment.setObjectKey(attachment.getId()+image.extension());
        attachment.setContentType(image.contentType()); attachment.setSize(image.bytes().length); attachment.setStatus("STAGED");
        attachment.setExpiresAt(Instant.now().plus(Duration.ofHours(24))); attachment.setSha256(hash(image.bytes()));
        Path target=objectPath(attachment);
        try { Files.write(target,image.bytes(),StandardOpenOption.CREATE_NEW); }
        catch(java.io.IOException ex) { throw new RepairException(503,"STORAGE_UNAVAILABLE","图片存储失败，请重试"); }
        record Fingerprint(String sha256,String purpose,Long storeId,Long ticketId,Long purchaseOrderId) {}
        try {
            var response=idempotency.execute(actor,"attachment:upload",key,new Fingerprint(attachment.getSha256(),purpose.name(),storeId,ticketId,purchaseOrderId),201,()->{
                mapper.insert(attachment); return view(attachment);
            });
            if(!attachment.getId().equals(response.getBody().path("id").asText())) Files.deleteIfExists(target);
            return response;
        } catch(RuntimeException | java.io.IOException ex) {
            // An uncertain commit keeps the private orphan for a bounded later cleanup, never deletes a referenced file.
            try { if(mapper.attachment(attachment.getId())==null) Files.deleteIfExists(target); } catch(Exception ignored) { }
            if(ex instanceof RuntimeException runtime) throw runtime;
            throw new RepairException(503,"STORAGE_UNAVAILABLE","图片存储服务暂不可用");
        }
    }
    public Attachment readable(long actor,String id) {
        Member member=access.active(actor); Attachment attachment=mapper.attachment(id);
        if(attachment==null) throw RepairException.missing();
        if("STAGED".equals(attachment.getStatus())) {
            if(!Long.valueOf(actor).equals(attachment.getUploaderId()) || !attachment.getExpiresAt().isAfter(Instant.now())) throw RepairException.missing();
        } else if("BOUND".equals(attachment.getStatus()) && attachment.getTicketId()!=null) {
            tickets.visible(actor,attachment.getTicketId());
            if((attachment.getPurpose().equals("ORDER_SCREENSHOT") || attachment.getPurpose().equals("RECEIPT")) && member.getRole()!=Role.HQ_ADMIN) throw RepairException.forbidden();
        } else throw RepairException.missing();
        return attachment;
    }
    public FileSystemResource resource(long actor,String id) {
        Path file=objectPath(readable(actor,id));
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new RepairException(503,"STORAGE_UNAVAILABLE","图片暂时无法读取，请稍后重试");
        return new FileSystemResource(file);
    }
    public void bind(long actor,long storeId,long ticketId,long eventId,Purpose purpose,List<String> requested) {
        List<String> ids=requested==null?List.of():requested;
        if(ids.size()>6 || ids.stream().distinct().count()!=ids.size()) throw RepairException.invalid("单次最多绑定 6 张不同图片");
        for(String id:ids.stream().sorted().toList()) {
            Attachment attachment=mapper.lock(id);
            if(attachment==null || !Long.valueOf(actor).equals(attachment.getUploaderId()) || !Long.valueOf(storeId).equals(attachment.getStoreId())
                    || !purpose.name().equals(attachment.getPurpose()) || !"STAGED".equals(attachment.getStatus())
                    || !attachment.getExpiresAt().isAfter(Instant.now()) || (attachment.getTicketId()!=null && !Long.valueOf(ticketId).equals(attachment.getTicketId())))
                throw RepairException.invalid("图片已过期、已被使用或不属于本次操作");
            if(mapper.bind(id,actor,storeId,ticketId,purpose.name(),eventId)!=1) throw new RepairException(409,"ATTACHMENT_CONFLICT","图片绑定冲突，请重新上传");
        }
    }
    public List<AttachmentView> images(long ticketId,Long eventId) { return mapper.ticketImages(ticketId,eventId).stream().map(AttachmentService::view).toList(); }
    public List<AttachmentView> purchaseImages(long orderId,String purpose,Long eventId) { return mapper.purchaseImages(orderId,purpose,eventId).stream().map(AttachmentService::view).toList(); }
    public void bindPurchase(long actor,long storeId,long ticketId,long orderId,long eventId,Purpose purpose,List<String> requested) {
        List<String> ids=requested==null?List.of():requested;
        if(ids.size()>6 || ids.stream().distinct().count()!=ids.size()) throw RepairException.invalid("单次最多绑定 6 张不同图片");
        for(String id:ids.stream().sorted().toList()) {
            Attachment image=mapper.lock(id);
            if(image==null || !Long.valueOf(orderId).equals(image.getPurchaseOrderId())) throw RepairException.invalid("图片不属于该采购单");
        }
        bind(actor,storeId,ticketId,eventId,purpose,ids);
    }
    public byte[] bytes(Attachment attachment) {
        try { return Files.readAllBytes(objectPath(attachment)); }
        catch(java.io.IOException ex) { throw new RepairException(503,"STORAGE_UNAVAILABLE","图片暂时无法读取"); }
    }
    public Path privateRoot() { return root; }
    public boolean deletePrivateObject(Attachment attachment) {
        try { Files.deleteIfExists(objectPath(attachment)); return true; }
        catch(java.io.IOException | IllegalStateException ex) { return false; }
    }
    private Path objectPath(Attachment attachment) {
        if(!attachment.getObjectKey().matches("[a-f0-9-]{36}\\.(png|jpg)")) throw new IllegalStateException("Invalid repair object key");
        Path path=root.resolve(attachment.getObjectKey()).normalize();
        if(!path.getParent().equals(root) || Files.isSymbolicLink(path)) throw new IllegalStateException("Invalid private storage path");
        return path;
    }
    private static String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    public static AttachmentView view(Attachment a) { return new AttachmentView(a.getId(),a.getPurpose(),a.getStatus(),a.getSize(),a.getContentType(),a.getExpiresAt()); }
}
