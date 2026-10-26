package com.gaofeng.repair;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import static com.gaofeng.repair.RepairIdentityIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="repair.integration",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepairWorkflowIntegrationTest {
    static String storeA,storeB,category,managerA,managerB,engineer1,engineer2,engineerId1,engineerId2;
    @BeforeAll static void setup() throws Exception {
        RepairIdentityIntegrationTest.prepare();
        seed();
    }
    static void seed() throws Exception {
        storeA=createStore("WORK-A"); storeB=createStore("WORK-B");
        category=write("POST","/api/v1/repair/categories",Map.of("code","COOLING","name","制冷设备","status","ACTIVE"),hq).body().path("id").asText();
        inviteAndActivate("a@workflow.example","STORE_MANAGER",List.of(storeA)); inviteAndActivate("b@workflow.example","STORE_MANAGER",List.of(storeB));
        inviteAndActivate("one@workflow.example","ENGINEER",List.of()); inviteAndActivate("two@workflow.example","ENGINEER",List.of());
        managerA=accessToken("a@workflow.example"); managerB=accessToken("b@workflow.example");
        var one=login("one@workflow.example").body(); var two=login("two@workflow.example").body();
        engineer1=one.path("token").path("accessToken").asText(); engineerId1=one.path("user").path("id").asText();
        engineer2=two.path("token").path("accessToken").asText(); engineerId2=two.path("user").path("id").asText();
    }
    @AfterAll static void close() throws Exception { RepairIdentityIntegrationTest.cleanup(); }
    static String accessToken(String email) throws Exception { return login(email).body().path("token").path("accessToken").asText(); }
    static Map<String,Object> assetBody(String code) { return new LinkedHashMap<>(Map.of("storeId",storeA,"assetCode",code,"name","立式冷柜","zone","后厨东侧","brand","原设备品牌","model","OLD-MODEL-01","serialNumber","SYNTHETIC-001")); }
    static JsonNode asset(String code) throws Exception {
        var response=write("POST","/api/v1/repair/assets",assetBody(code),hq); assertThat(response.status()).isEqualTo(201); return response.body();
    }
    static Map<String,Object> reportBody(String assetId,String title,List<String> pictures) {
        var body=new LinkedHashMap<String,Object>(); body.put("storeId",storeA); body.put("categoryId",category); body.put("title",title);
        body.put("description","冷柜温度持续偏高，已暂存食材，请安排检查。"); body.put("facilityName","手填名称不能覆盖台账"); body.put("zone","手填位置");
        body.put("brand","伪造品牌"); body.put("model","伪造型号"); body.put("contactName","门店值班经理"); body.put("contactPhone","13000000001");
        body.put("priority","URGENT"); body.put("attachmentIds",pictures); if(assetId!=null) body.put("assetId",assetId); return body;
    }
    static JsonNode report(String assetId,String title) throws Exception {
        var response=write("POST","/api/v1/repair/tickets",reportBody(assetId,title,List.of()),managerA); assertThat(response.status()).isEqualTo(201); return response.body();
    }
    static JsonNode detail(String id,String token) throws Exception { var result=request("GET","/api/v1/repair/tickets/"+id,null,token,null); assertThat(result.status()).isEqualTo(200); return result.body(); }
    static Result command(String id,String action,String token,Map<String,Object> extra) throws Exception {
        var body=new LinkedHashMap<>(extra); body.put("expectedVersion",detail(id,token).path("version").asInt());
        return write("POST","/api/v1/repair/tickets/"+id+"/"+action,body,token);
    }
    static void ok(String id,String action,String token,Map<String,Object> extra) throws Exception { var result=command(id,action,token,extra); assertThat(result.status()).describedAs(result.body().toString()).isEqualTo(200); }
    static String start(String id) throws Exception {
        ok(id,"approve",hq,Map.of("procurementRequired",false,"resolutionPlan","REPAIR","priority","URGENT"));
        ok(id,"assign",hq,Map.of("engineerId",engineerId1)); ok(id,"accept",engineer1,Map.of()); ok(id,"start",engineer1,Map.of()); return id;
    }
    static byte[] picture() throws Exception {
        var image=new BufferedImage(80,60,BufferedImage.TYPE_INT_RGB); var graphics=image.createGraphics(); graphics.setColor(java.awt.Color.BLUE); graphics.fillRect(0,0,80,60); graphics.dispose();
        var bytes=new ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"png",bytes); return bytes.toByteArray();
    }
    static Result upload(String token,String purpose,String storeId,String ticketId,String key) throws Exception {
        return upload(token,purpose,storeId,ticketId,key,null);
    }
    static Result upload(String token,String purpose,String storeId,String ticketId,String key,String purchaseOrderId) throws Exception {
        String boundary="RepairBoundary"+UUID.randomUUID().toString().replace("-",""); var output=new ByteArrayOutputStream();
        var fields=new LinkedHashMap<String,String>(); fields.put("purpose",purpose); if(storeId!=null) fields.put("storeId",storeId); if(ticketId!=null) fields.put("ticketId",ticketId);
        if(purchaseOrderId!=null) fields.put("purchaseOrderId",purchaseOrderId);
        for(var field:fields.entrySet()) output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes()); output.write(picture()); output.write(("\r\n--"+boundary+"--\r\n").getBytes());
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/repair/attachments")).header("Authorization","Bearer "+token)
                .header("Idempotency-Key",key).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(),json.readTree(response.body()),response.headers().firstValue("X-Request-Id").orElse(""));
    }
    static int imageStatus(String id,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+"/api/v1/repair/attachments/"+id+"/content")); if(token!=null) builder.header("Authorization","Bearer "+token);
        return client.send(builder.GET().build(),HttpResponse.BodyHandlers.ofByteArray()).statusCode();
    }
    @Test @Order(1) void completeRepairAndReturnedAcceptanceKeepSnapshotAndPrivateImages() throws Exception {
        String assetId=asset("FLOW-1").path("id").asText();
        var photo=upload(managerA,"FAULT",storeA,null,UUID.randomUUID().toString()); assertThat(photo.status()).isEqualTo(201);
        String photoId=photo.body().path("id").asText();
        assertThat(imageStatus(photoId,managerA)).isEqualTo(200); assertThat(imageStatus(photoId,managerB)).isEqualTo(404);
        var created=write("POST","/api/v1/repair/tickets",reportBody(assetId,"完整维修主线",List.of(photoId)),managerA); assertThat(created.status()).isEqualTo(201);
        String id=created.body().path("ticketId").asText();
        assertThat(detail(id,managerA).path("facilitySnapshot").path("model").asText()).isEqualTo("OLD-MODEL-01");
        assertThat(request("GET","/api/v1/repair/tickets/"+id,null,managerB,null).status()).isEqualTo(404);
        assertThat(request("GET","/api/v1/repair/tickets/"+id,null,engineer1,null).status()).isEqualTo(404);
        assertThat(command(id,"approve",managerA,Map.of("procurementRequired",false,"resolutionPlan","REPAIR","priority","NORMAL")).status()).isEqualTo(403);
        start(id);
        var current=detail(id,engineer1); assertThat(current.path("status").asText()).isEqualTo("IN_PROGRESS");
        String resultImage=upload(engineer1,"RESULT",null,id,UUID.randomUUID().toString()).body().path("id").asText();
        ok(id,"submit-result",engineer1,Map.of("resultNote","已清理冷凝器并恢复温控，实测温度正常。","attachmentIds",List.of(resultImage),"laborSuggestedAmount","160.5"));
        String events="/api/v1/repair/tickets/"+id+"/events";
        assertThat(request("GET",events,null,managerA,null).body().toString()).doesNotContain("160.50");
        assertThat(request("GET",events,null,hq,null).body().toString()).contains("160.50");
        ok(id,"return",managerA,Map.of("reason","运行一小时后仍有异响，请复查"));
        String secondResult=upload(engineer1,"RESULT",null,id,UUID.randomUUID().toString()).body().path("id").asText();
        ok(id,"submit-result",engineer1,Map.of("resultNote","已紧固风扇支架，连续运行稳定。","attachmentIds",List.of(secondResult)));
        ok(id,"confirm",managerA,Map.of("note","现场验收正常"));
        assertThat(detail(id,managerA).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(db.queryForObject("SELECT health_status FROM repair_assets WHERE id=?",String.class,assetId)).isEqualTo("NORMAL");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_active_assets WHERE asset_id=?",Long.class,assetId)).isZero();
        assertThat(request("GET",events,null,managerA,null).body().toString()).contains("运行一小时后仍有异响");
        assertThat(imageStatus(photoId,null)).isEqualTo(401); assertThat(imageStatus(photoId,managerB)).isEqualTo(404); assertThat(imageStatus(photoId,hq)).isEqualTo(200);
    }
    @Test @Order(2) void simultaneousReportsOccupyAssetOnce() throws Exception {
        String assetId=asset("CONCURRENT-REPORT").path("id").asText(); var body=reportBody(assetId,"并发报修",List.of());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->write("POST","/api/v1/repair/tickets",body,managerA)); var second=pool.submit(()->write("POST","/api/v1/repair/tickets",body,managerA));
            var a=first.get(15,TimeUnit.SECONDS); var b=second.get(15,TimeUnit.SECONDS);
            assertThat(List.of(a.status(),b.status())).containsExactlyInAnyOrder(201,409);
            assertThat((a.status()==409?a:b).body().path("code").asText()).isEqualTo("ASSET_HAS_ACTIVE_TICKET");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_tickets WHERE asset_id=?",Long.class,assetId)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test @Order(3) void concurrentDispatchAndReassignmentRevokeOldEngineer() throws Exception {
        String id=report(asset("ASSIGNMENT").path("id").asText(),"派单并发及改派").path("ticketId").asText();
        ok(id,"approve",hq,Map.of("procurementRequired",false,"resolutionPlan","REPAIR","priority","NORMAL"));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->write("POST","/api/v1/repair/tickets/"+id+"/assign",Map.of("expectedVersion",1,"engineerId",engineerId1),hq));
            var second=pool.submit(()->write("POST","/api/v1/repair/tickets/"+id+"/assign",Map.of("expectedVersion",1,"engineerId",engineerId2),hq));
            var a=first.get(15,TimeUnit.SECONDS); var b=second.get(15,TimeUnit.SECONDS);
            assertThat(List.of(a.status(),b.status())).containsExactlyInAnyOrder(200,409);
        } finally { pool.shutdownNow(); }
        String assignee=detail(id,hq).path("assigneeId").asText(); boolean one=assignee.equals(engineerId1); String old=one?engineer1:engineer2,next=one?engineer2:engineer1,nextId=one?engineerId2:engineerId1;
        ok(id,"accept",old,Map.of()); ok(id,"start",old,Map.of());
        String image=upload(old,"WORK_RECORD",null,id,UUID.randomUUID().toString()).body().path("id").asText();
        String recordKey=UUID.randomUUID().toString();
        var record=Map.of("expectedVersion",detail(id,old).path("version").asInt(),"note","原工程师排查记录保留","attachmentIds",List.of(image));
        assertThat(request("POST","/api/v1/repair/tickets/"+id+"/work-records",record,old,recordKey).status()).isEqualTo(200);
        ok(id,"reassign",hq,Map.of("engineerId",nextId,"reason","调整现场工程师"));
        assertThat(request("GET","/api/v1/repair/tickets/"+id,null,old,null).status()).isEqualTo(404); assertThat(imageStatus(image,old)).isEqualTo(404);
        assertThat(request("POST","/api/v1/repair/tickets/"+id+"/work-records",record,old,recordKey).status()).isEqualTo(404);
        assertThat(request("GET","/api/v1/repair/tickets/"+id+"/events",null,next,null).body().toString()).contains("原工程师排查记录保留");
    }
    @Test @Order(4) void invalidImageBindingRollsBackTicketAssetEventAndNotifications() throws Exception {
        String assetId=asset("ROLLBACK-IMAGE").path("id").asText(); long notifications=db.queryForObject("SELECT COUNT(*) FROM repair_notifications",Long.class);
        var response=write("POST","/api/v1/repair/tickets",reportBody(assetId,"绑定故障注入",List.of(UUID.randomUUID().toString())),managerA);
        assertThat(response.status()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_tickets WHERE asset_id=?",Long.class,assetId)).isZero();
        assertThat(db.queryForObject("SELECT health_status FROM repair_assets WHERE id=?",String.class,assetId)).isEqualTo("NORMAL");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_notifications",Long.class)).isEqualTo(notifications);
    }
    @Test @Order(5) void uploadReplayAndExpiredPreviewArePrivate() throws Exception {
        String key=UUID.randomUUID().toString(); var first=upload(managerA,"FAULT",storeA,null,key); var second=upload(managerA,"FAULT",storeA,null,key);
        assertThat(first.status()).isEqualTo(201); assertThat(second.body()).isEqualTo(first.body());
        String id=first.body().path("id").asText();
        assertThat(upload(managerB,"FAULT",storeA,null,UUID.randomUUID().toString()).status()).isEqualTo(404);
        db.update("UPDATE repair_attachments SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",id);
        assertThat(imageStatus(id,managerA)).isEqualTo(404);
    }
    @Test @Order(6) void unknownAssetLinkAndImmutableReportingSnapshot() throws Exception {
        String assetId=asset("LINKED").path("id").asText(),id=report(null,"待识别设施").path("ticketId").asText();
        ok(id,"asset-link",hq,Map.of("assetId",assetId,"reason","现场确认对应台账"));
        var assetNow=request("GET","/api/v1/repair/assets/"+assetId,null,hq,null).body(); var body=assetBody("LINKED"); body.put("expectedVersion",assetNow.path("version").asInt()); body.put("model","EDITED-LATER");
        assertThat(write("PUT","/api/v1/repair/assets/"+assetId,body,hq).status()).isEqualTo(200);
        assertThat(detail(id,managerA).path("facilitySnapshot").path("model").asText()).isEqualTo("OLD-MODEL-01");
        assertThat(request("GET","/api/v1/repair/tickets/"+id+"/events",null,hq,null).body().toString()).contains("手填名称不能覆盖台账");
        var retire=write("POST","/api/v1/repair/assets/"+assetId+"/retire",Map.of("expectedVersion",2,"reason","有工单时不能退役"),hq);
        assertThat(retire.status()).isEqualTo(409);
        assertThat(request("GET","/api/v1/repair/assets/"+assetId,null,managerB,null).status()).isEqualTo(404);
    }
    @Test @Order(98) @EnabledIfSystemProperty(named="repair.browser",matches="true") void browserWorkbench() throws Exception {
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/repair-workflow-browser"));
        java.nio.file.Files.write(java.nio.file.Path.of("target/repair-workflow-browser/sample.png"),picture());
        RepairBrowserSupport.run("repair-workflow-browser-test.cjs",java.nio.file.Path.of("target/repair-workflow-browser.log"),base);
    }
    @Test @Order(99) void disabledStoreAndCategoryBlockNewReportsButExistingWorkCanFinish() throws Exception {
        String id=report(asset("DISABLED-STORE").path("id").asText(),"停用后的存量工单").path("ticketId").asText();
        assertThat(write("PUT","/api/v1/repair/stores/"+storeA,Map.of("code","WORK-A","name","测试门店","region","华东","address","合成地址","status","DISABLED","expectedVersion",0),hq).status()).isEqualTo(200);
        assertThat(write("PUT","/api/v1/repair/categories/"+category,Map.of("code","COOLING","name","制冷设备","status","DISABLED","expectedVersion",0),hq).status()).isEqualTo(200);
        assertThat(write("POST","/api/v1/repair/tickets",reportBody(null,"停用后新报修",List.of()),managerA).status()).isEqualTo(400);
        start(id);
        String image=upload(engineer1,"RESULT",null,id,UUID.randomUUID().toString()).body().path("id").asText();
        ok(id,"submit-result",engineer1,Map.of("resultNote","存量维修完成","attachmentIds",List.of(image)));
        ok(id,"confirm",managerA,Map.of()); assertThat(detail(id,hq).path("status").asText()).isEqualTo("COMPLETED");
    }
}
