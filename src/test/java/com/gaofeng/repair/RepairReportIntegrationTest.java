package com.gaofeng.repair;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.gaofeng.repair.RepairIdentityIntegrationTest.*;
import static com.gaofeng.repair.RepairWorkflowIntegrationTest.*;
import static com.gaofeng.repair.RepairProcurementIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;
@EnabledIfSystemProperty(named="repair.integration",matches="true")
class RepairReportIntegrationTest {
    static final String QUERY="?from=2026-01-01T00:00:00Z&to=2026-02-01T00:00:00Z";static Purchase parts;
    @BeforeAll static void prepareReports()throws Exception{
        RepairWorkflowIntegrationTest.setup();parts=purchase("PART_REPAIR");assertThat(save(parts,List.of(item("PART",2,"100.00",true),item("PART",1,"50.00",true)),"5.00").status()).isEqualTo(200);place(parts);
        var lines=materials(parts,managerA).path("items");receive(parts,managerA,List.of(Map.of("purchaseItemId",lines.get(0).path("id").asText(),"quantity",2)));receive(parts,managerA,List.of(Map.of("purchaseItemId",lines.get(1).path("id").asText(),"quantity",1)));assignAndAccept(parts);repairResult(parts);ok(parts.ticketId(),"return",managerA,Map.of("reason","运行后仍有异响"));
        String image=upload(engineer1,"RESULT",null,parts.ticketId(),UUID.randomUUID().toString()).body().path("id").asText();ok(parts.ticketId(),"submit-result",engineer1,Map.of("resultNote","返修后验证完成","attachmentIds",List.of(image)));ok(parts.ticketId(),"confirm",managerA,Map.of());
        assertThat(write("PUT","/api/v1/repair/tickets/"+parts.ticketId()+"/costs",Map.of("expectedVersion",detail(parts.ticketId(),hq).path("version").asInt(),"laborAmount","20.00","otherAmount","5.00","note","核对完成"),hq).status()).isEqualTo(200);
        String second=report(asset("REPORT-PENDING").path("id").asText(),"=HYPERLINK(\"https://example.invalid\",\"x\")\n中,文").path("ticketId").asText();start(second);String photo=upload(engineer1,"RESULT",null,second,UUID.randomUUID().toString()).body().path("id").asText();ok(second,"submit-result",engineer1,Map.of("resultNote","完成待核算","attachmentIds",List.of(photo)));ok(second,"confirm",managerA,Map.of());
        String oldOpen=report(null,"范围外历史未结").path("ticketId").asText(),boundary=report(null,"右边界不计入提交数").path("ticketId").asText(),lastMillisecond=report(null,"范围内最后一毫秒").path("ticketId").asText();ok(lastMillisecond,"reject",hq,Map.of("reason","补充报修内容"));
        db.update("UPDATE repair_tickets SET submitted_at='2026-01-01 00:00:00',first_started_at='2026-01-12 00:00:00',completed_repair_at='2026-01-12 02:00:00',completed_at='2026-01-13 00:00:00' WHERE id=?",parts.ticketId());
        db.update("UPDATE repair_purchase_orders SET ordered_at='2026-01-10 00:00:00',received_at='2026-01-11 00:00:00' WHERE id=?",parts.orderId());
        db.update("UPDATE repair_tickets SET submitted_at='2025-12-31 23:59:59.999',first_started_at='2026-01-15 00:00:00',completed_repair_at='2026-01-15 04:00:00',completed_at='2026-01-16 00:00:00' WHERE id=?",second);
        db.update("UPDATE repair_tickets SET submitted_at='2025-12-20 00:00:00' WHERE id=?",oldOpen);db.update("UPDATE repair_tickets SET submitted_at='2026-02-01 00:00:00' WHERE id=?",boundary);db.update("UPDATE repair_tickets SET submitted_at='2026-01-31 23:59:59.999' WHERE id=?",lastMillisecond);
    }
    @AfterAll static void closeReports()throws Exception{RepairWorkflowIntegrationTest.close();}
    static JsonNode get(String path)throws Exception{var response=request("GET","/api/v1/repair/"+path,null,hq,null);assertThat(response.status()).describedAs(response.body().toString()).isEqualTo(200);return response.body();}
    @Test void totalsRespectIndependentDatesPendingCostsAndNoJoinMultiplication()throws Exception{
        var result=get("reports/overview"+QUERY);assertThat(result.path("submitted").asLong()).isEqualTo(2);assertThat(result.path("cohortCompleted").asLong()).isEqualTo(1);assertThat(result.path("completed").asLong()).isEqualTo(2);assertThat(result.path("currentOpen").asLong()).isEqualTo(2);
        assertThat(result.path("completionRate").asText()).isEqualTo("0.500000");assertThat(result.path("purchaseAmount").asText()).isEqualTo("255.00");assertThat(result.path("confirmedCompletedCostAmount").asText()).isEqualTo("280.00");assertThat(result.path("pendingCostCount").asLong()).isEqualTo(1);assertThat(result.path("confirmedCostCount").asLong()).isEqualTo(1);assertThat(result.path("averageRepairHours").asText()).isEqualTo("3.000");assertThat(result.path("averageArrivalHours").asText()).isEqualTo("24.000");
    }
    @Test void groupedReportsSeparateOriginalFacilitiesAndPurchasedItems()throws Exception{
        var report=get("reports/stores"+QUERY);assertThat(report.path("stores").path("total").asLong()).isEqualTo(2);var store=report.path("stores").path("items").get(0);assertThat(store.path("purchaseAmount").asText()).isEqualTo("255.00");assertThat(store.path("confirmedCompletedCostAmount").asText()).isEqualTo("280.00");
        assertThat(report.path("originalFacilityFaults").toString()).doesNotContain("NEW-MODEL-88");assertThat(report.path("purchasedItems").get(0).path("quantity").asLong()).isEqualTo(3);
        var engineers=get("reports/engineers"+QUERY).path("items");for(var engineer:engineers)if(engineer.path("id").asText().equals(engineerId1)){assertThat(engineer.path("completed").asLong()).isEqualTo(2);assertThat(engineer.path("returned").asLong()).isEqualTo(1);}
        var empty=get("reports/overview"+QUERY+"&storeId="+storeB);assertThat(empty.path("completionRate").isNull()).isTrue();assertThat(empty.path("averageRepairHours").isNull()).isTrue();assertThat(empty.path("purchaseAmount").asText()).isEqualTo("0.00");
    }
    @Test void dashboardAndReportsEnforceLiveScope()throws Exception{
        assertThat(request("GET","/api/v1/repair/reports/overview"+QUERY,null,managerA,null).status()).isEqualTo(403);assertThat(request("GET","/api/v1/repair/reports/export"+QUERY,null,engineer1,null).status()).isEqualTo(403);
        var empty=request("GET","/api/v1/repair/dashboard",null,managerB,null);assertThat(empty.status()).isEqualTo(200);assertThat(empty.body().path("statuses")).isEmpty();assertThat(empty.body().toString()).doesNotContain("purchaseAmount");
        assertThat(request("GET","/api/v1/repair/reports/overview?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z",null,hq,null).status()).isEqualTo(400);
    }
    @Test void csvUsesBomAndEscapesFormulasAndQuotes()throws Exception{
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/repair/reports/export?from=2025-12-01T00:00:00Z&to=2026-02-01T00:00:00Z")).header("Authorization","Bearer "+hq).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);assertThat(Arrays.copyOf(response.body(),3)).containsExactly((byte)0xef,(byte)0xbb,(byte)0xbf);String content=new String(response.body(),StandardCharsets.UTF_8);assertThat(content).contains("\"'=HYPERLINK(\"\"https://example.invalid\"\",\"\"x\"\")\n中,文\"");assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/csv");
    }
    @Test void exportEnforcesTenThousandRowLimitBeforeStreaming()throws Exception{
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        String sql="INSERT INTO repair_tickets(ticket_no,store_id,reporter_id,category_id,title,description,facility_snapshot,contact_name,contact_phone,priority,status,submitted_at) SELECT CONCAT('LIMIT-',ROW_NUMBER() OVER()),t.store_id,t.reporter_id,t.category_id,'合成导出上限样本',t.description,t.facility_snapshot,t.contact_name,t.contact_phone,t.priority,'SUBMITTED','2026-01-20 00:00:00' FROM repair_tickets t CROSS JOIN "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits+" d CROSS JOIN "+digits+" e WHERE t.id=? LIMIT 10001";
        try{db.update(sql,parts.ticketId());var response=request("GET","/api/v1/repair/reports/export"+QUERY,null,hq,null);assertThat(response.status()).isEqualTo(422);assertThat(response.body().path("code").asText()).isEqualTo("EXPORT_LIMIT_EXCEEDED");}finally{db.update("DELETE FROM repair_tickets WHERE ticket_no LIKE 'LIMIT-%'");}
    }
    @Test @EnabledIfSystemProperty(named="repair.browser",matches="true") void browserReportsAndNotifications()throws Exception{
        java.nio.file.Path output=java.nio.file.Path.of("target/repair-report-browser");java.nio.file.Files.createDirectories(output);
        RepairBrowserSupport.run("repair-report-browser-test.cjs",output.resolve("browser.log"),base);
    }
}
