package com.gaofeng.repair.report.api;
import com.gaofeng.repair.access.api.dto.AccessDtos.Page;
import com.gaofeng.repair.access.service.RepairAccess;
import com.gaofeng.repair.report.api.dto.ReportDtos.*;
import com.gaofeng.repair.report.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.time.Instant;
@RestController @RequestMapping("/api/v1/repair") @RequiredArgsConstructor
public class ReportController {
    private final ReportService service;
    @GetMapping("/dashboard") public Dashboard dashboard(@AuthenticationPrincipal Jwt jwt){return service.dashboard(RepairAccess.userId(jwt));}
    @GetMapping("/dashboard/engineer-dispatch") public Page<EngineerDispatchRow> engineerDispatch(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){return service.engineerDispatch(RepairAccess.userId(jwt),page,size);}
    @GetMapping("/dashboard/engineer-dispatch/{engineerId}/tickets") public Page<DispatchTicket> dispatchTickets(@AuthenticationPrincipal Jwt jwt,@PathVariable long engineerId,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){return service.dispatchTickets(RepairAccess.userId(jwt),engineerId,status,page,size);}
    @GetMapping("/reports/overview") public Overview overview(@AuthenticationPrincipal Jwt jwt,@RequestParam Instant from,@RequestParam Instant to,@RequestParam(required=false) Long storeId){long actor=RepairAccess.userId(jwt);return service.overview(actor,service.period(actor,from,to,storeId));}
    @GetMapping("/reports/stores") public StoreReport stores(@AuthenticationPrincipal Jwt jwt,@RequestParam Instant from,@RequestParam Instant to,@RequestParam(required=false) Long storeId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){long actor=RepairAccess.userId(jwt);return service.stores(actor,service.period(actor,from,to,storeId),page,size);}
    @GetMapping("/reports/engineers") public Page<EngineerRow> engineers(@AuthenticationPrincipal Jwt jwt,@RequestParam Instant from,@RequestParam Instant to,@RequestParam(required=false) Long storeId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){long actor=RepairAccess.userId(jwt);return service.engineers(actor,service.period(actor,from,to,storeId),page,size);}
    @GetMapping("/reports/export") public ResponseEntity<StreamingResponseBody> export(@AuthenticationPrincipal Jwt jwt,@RequestParam Instant from,@RequestParam Instant to,@RequestParam(required=false) Long storeId){long actor=RepairAccess.userId(jwt);var rows=service.export(actor,service.period(actor,from,to,storeId));return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=repair-tickets.csv").body(stream->RepairCsv.write(stream,rows));}
}
