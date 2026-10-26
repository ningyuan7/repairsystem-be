package com.gaofeng.repair.ocr.service;
import com.gaofeng.repair.common.*;
import com.gaofeng.repair.ocr.api.dto.OcrDtos.Extraction;
import com.gaofeng.repair.ocr.mapper.OcrMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class OcrSourceValidator {
    private final OcrMapper mapper; private final JsonSupport json;
    public void validate(long orderId,String jobId,Integer candidateIndex) {
        if(jobId==null) { if(candidateIndex!=null) throw RepairException.invalid("采购明细缺少 OCR 来源任务"); return; }
        var job=mapper.get(jobId);
        if(job==null || job.getPurchaseOrderId()!=orderId || !"SUCCEEDED".equals(job.getStatus())) throw RepairException.invalid("OCR 来源不是当前采购单的成功任务");
        if(candidateIndex!=null && candidateIndex>=json.read(job.getResult(),Extraction.class).candidates().items().size()) throw RepairException.invalid("OCR 候选项不存在");
    }
}
