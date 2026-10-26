package com.gaofeng.repair.attachment.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.gaofeng.common.ratelimit.*;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.attachment.api.dto.AttachmentDtos.*;
import com.gaofeng.repair.attachment.service.AttachmentService;
import com.gaofeng.repair.procurement.service.ProcurementService;
import com.gaofeng.repair.procurement.api.dto.ProcurementDtos.VersionInput;
import com.gaofeng.repair.common.IdempotencyService;
import com.gaofeng.repair.common.RepairException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.Duration;
@RestController
@RequestMapping("/api/v1/repair/attachments")
@RequiredArgsConstructor
public class AttachmentController {
    private final AttachmentService service;
    private final RedisRateLimiter limiter;
    private final AbuseProtectionProperties protection;
    private final ProcurementService procurement;
    private final IdempotencyService idempotency;
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JsonNode> upload(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,
            @RequestParam MultipartFile file,@RequestParam Purpose purpose,@RequestParam(required=false) Long storeId,
            @RequestParam(required=false) Long ticketId,@RequestParam(required=false) Long purchaseOrderId) throws java.io.IOException {
        long actor=RepairAccess.userId(jwt); limiter.check("repair-upload",Long.toString(actor),protection.getUploadPerMinute(),Duration.ofMinutes(1));
        return service.upload(actor,key,purpose,storeId,ticketId,purchaseOrderId,file.getBytes(),file.getContentType());
    }
    @GetMapping("/{id}/content") public ResponseEntity<Resource> content(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {
        long actor=RepairAccess.userId(jwt); var attachment=service.readable(actor,id); var resource=service.resource(actor,id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(attachment.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\"repair-image\"").contentLength(attachment.getSize()).body(resource);
    }
    @GetMapping("/{id}/download-url") public Download url(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {
        service.readable(RepairAccess.userId(jwt),id); return new Download("/api/v1/repair/attachments/"+id+"/content",0,true);
    }
    @PostMapping("/{id}/bind-order") public ResponseEntity<JsonNode> bindOrder(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody VersionInput body) {
        long actor=RepairAccess.userId(jwt); Long orderId=service.readable(actor,id).getPurchaseOrderId();
        if(orderId==null) throw RepairException.invalid("图片未关联采购草稿");
        return idempotency.execute(actor,"attachment:"+id+":bind-order",key,body,200,()->procurement.bindScreenshot(actor,id,orderId,body));
    }
}
