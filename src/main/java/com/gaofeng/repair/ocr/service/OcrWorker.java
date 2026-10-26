package com.gaofeng.repair.ocr.service;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.attachment.mapper.AttachmentMapper;
import com.gaofeng.repair.attachment.service.AttachmentService;
import com.gaofeng.repair.common.JsonSupport;
import com.gaofeng.repair.ocr.domain.OcrJob;
import com.gaofeng.repair.ocr.mapper.OcrMapper;
import com.gaofeng.repair.procurement.domain.PurchaseStatus;
import com.gaofeng.repair.procurement.mapper.ProcurementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

@Component @RequiredArgsConstructor
public class OcrWorker {
    private final OcrMapper mapper; private final ProcurementMapper purchases; private final RepairAccess access;
    private final AttachmentMapper images; private final AttachmentService storage; private final OrderOcrClient client;
    private final OrderFieldExtractor extractor; private final JsonSupport json; private final PlatformTransactionManager transactions;
    @Value("${repair.workers.enabled:true}") private boolean enabled;
    @Scheduled(fixedDelayString="${repair.ocr.poll-ms:2000}",initialDelayString="${repair.ocr.poll-ms:2000}")
    public void poll() { if(enabled && client.configured()) runOnce(); }
    public boolean runOnce() {
        TransactionTemplate tx=new TransactionTemplate(transactions);
        OcrJob job=tx.execute(status->{ mapper.exhausted(); OcrJob next=mapper.next(); if(next==null)return null; next.setLeaseToken(UUID.randomUUID().toString()); mapper.claim(next.getId(),next.getLeaseToken()); return mapper.get(next.getId()); });
        if(job==null) return false;
        if(!authorized(job)) { finish(job,"CANCELLED",null,"REQUESTER_OR_PURCHASE_CHANGED",null); return true; }
        try {
            var image=images.attachment(job.getSourceAttachmentId());
            if(image==null || !"BOUND".equals(image.getStatus()) || !"ORDER_SCREENSHOT".equals(image.getPurpose()) || !Objects.equals(image.getPurchaseOrderId(),job.getPurchaseOrderId()) || !Objects.equals(image.getSha256(),job.getSourceSha256())) {
                finish(job,"FAILED",null,"SOURCE_CHANGED",null); return true;
            }
            byte[] bytes=storage.bytes(image);
            String actualHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if(!actualHash.equals(job.getSourceSha256())) { finish(job,"FAILED",null,"SOURCE_CHANGED",null); return true; }
            var recognition=client.recognize(bytes);
            finish(job,"SUCCEEDED",json.write(extractor.extract(recognition)),null,recognition.requestId());
        } catch(OrderOcrClient.OcrFailure ex) { finish(job,ex.transientFailure() && job.getAttempts()<2?"QUEUED":"FAILED",null,ex.code(),ex.requestId()); }
        catch(Exception ex) { finish(job,"FAILED",null,"OCR_PROCESSING_FAILED",null); }
        return true;
    }
    /** Fenced CAS: a reclaimed/expired worker cannot persist even a successful late response. */
    public int finish(OcrJob job,String status,String result,String error,String requestId) {
        return new TransactionTemplate(transactions).execute(tx->{
            var order=purchases.lockOrder(job.getPurchaseOrderId());
            if(order==null || !authorized(job)) return mapper.finish(job.getId(),job.getLeaseToken(),"CANCELLED",null,"REQUESTER_OR_PURCHASE_CHANGED",requestId,Instant.now());
            return mapper.finish(job.getId(),job.getLeaseToken(),status,result,error,requestId,Instant.now().plusSeconds("QUEUED".equals(status)?30:0));
        });
    }
    private boolean authorized(OcrJob job) { try { access.hq(job.getRequesterId()); var order=purchases.order(job.getPurchaseOrderId()); return order!=null && order.getStatus()==PurchaseStatus.DRAFT; } catch(com.gaofeng.repair.common.RepairException ex) { return false; } }
}
