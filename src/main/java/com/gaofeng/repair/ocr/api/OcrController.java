package com.gaofeng.repair.ocr.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.common.IdempotencyService;
import com.gaofeng.repair.ocr.api.dto.OcrDtos.*;
import com.gaofeng.repair.ocr.service.OcrService;
import com.gaofeng.repair.procurement.service.PurchaseAccess;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/v1/repair") @RequiredArgsConstructor
public class OcrController {
    private final OcrService service; private final PurchaseAccess purchases; private final IdempotencyService idempotency;
    @GetMapping("/ocr/availability") public Availability availability(@AuthenticationPrincipal Jwt jwt) { return service.availability(RepairAccess.userId(jwt)); }
    @GetMapping("/purchase-orders/{id}/ocr-jobs") public List<JobView> list(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) { return service.list(RepairAccess.userId(jwt),id); }
    @PostMapping("/purchase-orders/{id}/ocr-jobs") public ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Create body) {
        long actor=RepairAccess.userId(jwt); purchases.hq(actor,id); return idempotency.execute(actor,"purchase:"+id+":ocr-create",key,body,202,()->service.create(actor,id,body));
    }
    @GetMapping("/ocr-jobs/{id}") public JobView get(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) { return service.get(RepairAccess.userId(jwt),id); }
    @PostMapping("/ocr-jobs/{id}/retry") public ResponseEntity<JsonNode> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Retry body) {
        long actor=RepairAccess.userId(jwt); service.visible(actor,id); return idempotency.execute(actor,"ocr:"+id+":retry",key,body,202,()->service.retry(actor,id,body));
    }
}
