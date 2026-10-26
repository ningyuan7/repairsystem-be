package com.gaofeng.repair.ocr.service;
import com.gaofeng.common.ratelimit.RedisRateLimiter;
import com.gaofeng.repair.access.service.*;
import com.gaofeng.repair.attachment.domain.Attachment;
import com.gaofeng.repair.attachment.service.AttachmentService;
import com.gaofeng.repair.common.*;
import com.gaofeng.repair.ocr.api.dto.OcrDtos.*;
import com.gaofeng.repair.ocr.domain.OcrJob;
import com.gaofeng.repair.ocr.mapper.OcrMapper;
import com.gaofeng.repair.procurement.domain.PurchaseStatus;
import com.gaofeng.repair.procurement.mapper.ProcurementMapper;
import com.gaofeng.repair.procurement.service.PurchaseAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.*;

@Service @RequiredArgsConstructor
public class OcrService {
    private final OcrMapper mapper; private final ProcurementMapper purchases; private final PurchaseAccess visibility;
    private final RepairAccess access; private final AttachmentService attachments; private final OrderOcrClient client;
    private final JsonSupport json; private final RedisRateLimiter limiter; private final AccessService audit;
    @Value("${repair.ocr.per-minute:5}") private int perMinute;
    @Value("${repair.ocr.per-day:100}") private int perDay;
    public Availability availability(long actor) { access.hq(actor); return new Availability(client.configured(),client.provider()); }
    @Transactional public JobView create(long actor,long orderId,Create body) {
        visibility.hq(actor,orderId); requireConfigured(); var order=purchases.lockOrder(orderId);
        if(order.getStatus()!=PurchaseStatus.DRAFT) throw new RepairException(409,"INVALID_TRANSITION","仅采购草稿可发起识别");
        Attachment image=attachments.readable(actor,body.attachmentId());
        if(!"BOUND".equals(image.getStatus()) || !"ORDER_SCREENSHOT".equals(image.getPurpose()) || !Objects.equals(image.getPurchaseOrderId(),orderId)) throw RepairException.invalid("请选择已绑定到本采购单的订单截图");
        limiter.check("repair-ocr-minute",Long.toString(actor),perMinute,Duration.ofMinutes(1)); limiter.check("repair-ocr-day",Long.toString(actor),perDay,Duration.ofDays(1));
        OcrJob job=new OcrJob(); job.setId(UUID.randomUUID().toString()); job.setPurchaseOrderId(orderId); job.setSourceAttachmentId(image.getId()); job.setSourceSha256(image.getSha256()); job.setRequesterId(actor); job.setProvider(client.provider());
        mapper.insert(job); audit.audit(actor,"OCR_CREATE","PURCHASE",Long.toString(orderId),Map.of("jobId",job.getId(),"attachmentId",image.getId())); return view(mapper.get(job.getId()));
    }
    @Transactional(readOnly=true) public JobView get(long actor,String id) { return view(visible(actor,id)); }
    @Transactional(readOnly=true) public List<JobView> list(long actor,long orderId) { visibility.hq(actor,orderId); return mapper.forOrder(orderId).stream().map(this::view).toList(); }
    @Transactional public JobView retry(long actor,String id,Retry body) {
        OcrJob initial=visible(actor,id); requireConfigured(); var order=purchases.lockOrder(initial.getPurchaseOrderId());
        if(order.getStatus()!=PurchaseStatus.DRAFT) throw new RepairException(409,"INVALID_TRANSITION","采购已结束编辑，不能重新识别");
        OcrJob job=mapper.lock(id); RepairException.version(body.expectedVersion(),job.getVersion());
        if(!"FAILED".equals(job.getStatus())) throw new RepairException(409,"INVALID_TRANSITION","仅失败任务可重新发起识别");
        // A user retry is a new paid-operation authorization and leaves the failed task intact.
        return create(actor,job.getPurchaseOrderId(),new Create(job.getSourceAttachmentId()));
    }
    public OcrJob visible(long actor,String id) { OcrJob job=mapper.get(id); if(job==null) throw RepairException.missing(); visibility.hq(actor,job.getPurchaseOrderId()); return job; }
    public JobView view(OcrJob job) { return new JobView(job.getId(),job.getPurchaseOrderId(),job.getSourceAttachmentId(),job.getStatus(),job.getAttempts(),job.getVersion(),job.getResult()==null?null:json.read(job.getResult(),Extraction.class),job.getErrorCode(),job.getProvider(),job.getProviderRequestId(),job.getCreatedAt()); }
    private void requireConfigured() { if(!client.configured()) throw new RepairException(503,"OCR_NOT_CONFIGURED","尚未启用订单识别，请继续手工填写采购信息"); }
}
