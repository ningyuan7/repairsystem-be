package com.gaofeng.repair.asset.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.gaofeng.repair.access.api.dto.AccessDtos.Page;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.asset.api.dto.AssetDtos.*;
import com.gaofeng.repair.asset.service.AssetService;
import com.gaofeng.repair.common.IdempotencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1/repair")
@RequiredArgsConstructor
public class AssetController {
    private final AssetService service;
    private final IdempotencyService idempotency;
    @GetMapping("/categories") public List<CategoryView> categories(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="false") boolean includeDisabled) { return service.categories(RepairAccess.userId(jwt),includeDisabled); }
    @PostMapping("/categories") public ResponseEntity<JsonNode> category(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CategoryRequest body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"category:create",key,body,201,()->service.saveCategory(actor,null,body));
    }
    @PutMapping("/categories/{id}") public ResponseEntity<JsonNode> categoryUpdate(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CategoryRequest body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"category:"+id+":update",key,body,200,()->service.saveCategory(actor,id,body));
    }
    @GetMapping("/assets") public Page<AssetView> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) Long storeId,
            @RequestParam(required=false) Condition condition,@RequestParam(required=false) Lifecycle lifecycle,@RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return service.list(RepairAccess.userId(jwt),storeId,condition,lifecycle,keyword,page,size); }
    @GetMapping("/assets/{id}") public AssetView get(@AuthenticationPrincipal Jwt jwt,@PathVariable long id) { return service.get(RepairAccess.userId(jwt),id); }
    @PostMapping("/assets") public ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody AssetRequest body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"asset:create",key,body,201,()->service.save(actor,null,body));
    }
    @PutMapping("/assets/{id}") public ResponseEntity<JsonNode> update(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody AssetRequest body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"asset:"+id+":update",key,body,200,()->service.save(actor,id,body));
    }
    @PostMapping("/assets/{id}/condition") public ResponseEntity<JsonNode> condition(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ConditionRequest body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"asset:"+id+":condition",key,body,200,()->service.condition(actor,id,body));
    }
    @PostMapping("/assets/{id}/retire") public ResponseEntity<JsonNode> retire(@AuthenticationPrincipal Jwt jwt,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody RetireRequest body) {
        long actor=RepairAccess.userId(jwt); return idempotency.execute(actor,"asset:"+id+":retire",key,body,200,()->service.retire(actor,id,body));
    }
}
