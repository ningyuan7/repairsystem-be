package com.gaofeng.repair;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.gaofeng.repair.RepairIdentityIntegrationTest.*;
import static com.gaofeng.repair.RepairWorkflowIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="repair.integration",matches="true")
class RepairProcurementIntegrationTest {
    @BeforeAll static void prepareEnvironment() throws Exception { RepairWorkflowIntegrationTest.setup(); }
    @AfterAll static void cleanEnvironment() throws Exception { RepairWorkflowIntegrationTest.close(); }
    record Purchase(String ticketId,String assetId,String orderId) {}
    static Purchase purchase(String plan) throws Exception {
        String assetId=asset("PUR-"+UUID.randomUUID().toString().substring(0,8)).path("id").asText();
        String ticketId=report(assetId,"采购测试 "+UUID.randomUUID().toString().substring(0,8)).path("ticketId").asText();
        ok(ticketId,"approve",hq,Map.of("procurementRequired",true,"resolutionPlan",plan,"priority","NORMAL"));
        var created=write("POST","/api/v1/repair/tickets/"+ticketId+"/purchase-orders",Map.of("expectedTicketVersion",detail(ticketId,hq).path("version").asInt()),hq);
        assertThat(created.status()).isEqualTo(201); return new Purchase(ticketId,assetId,created.body().path("purchaseOrderId").asText());
    }
    static JsonNode order(Purchase purchase) throws Exception { var result=request("GET","/api/v1/repair/purchase-orders/"+purchase.orderId(),null,hq,null); assertThat(result.status()).isEqualTo(200); return result.body(); }
    static Map<String,Object> item(String purpose,int quantity,String price,boolean confirmed) { return new LinkedHashMap<>(Map.of("purpose",purpose,"name",purpose.equals("REPLACEMENT")?"替换用冷柜":"冷柜温控器","brand","新采购品牌","model","NEW-MODEL-88","quantity",quantity,"unitPrice",price,"specConfirmed",confirmed)); }
    static Map<String,Object> versions(Purchase p) throws Exception { var order=order(p); return new LinkedHashMap<>(Map.of("expectedVersion",order.path("version").asInt(),"expectedTicketVersion",detail(p.ticketId(),hq).path("version").asInt())); }
    static Result save(Purchase p,List<Map<String,Object>> items,String adjustment) throws Exception {
        var body=versions(p); body.put("supplier","合成供应商（总部专用）"); body.put("orderNumber","SYNTHETIC-ORDER-PRIVACY"); body.put("orderedAt",Instant.now().minusSeconds(30).toString()); body.put("currency","CNY"); body.put("items",items); body.put("adjustmentAmount",adjustment);
        return write("PUT","/api/v1/repair/purchase-orders/"+p.orderId(),body,hq);
    }
    static void place(Purchase p) throws Exception { var result=write("POST","/api/v1/repair/purchase-orders/"+p.orderId()+"/place",versions(p),hq); assertThat(result.status()).describedAs(result.body().toString()).isEqualTo(200); }
    static JsonNode materials(Purchase p,String token) throws Exception { var result=request("GET","/api/v1/repair/tickets/"+p.ticketId()+"/materials",null,token,null); assertThat(result.status()).isEqualTo(200); return result.body(); }
    static Result receive(Purchase p,String token,List<Map<String,Object>> quantities) throws Exception { var summary=materials(p,token); return write("POST","/api/v1/repair/purchase-orders/"+p.orderId()+"/receipts",Map.of("expectedVersion",summary.path("purchaseOrderVersion").asInt(),"expectedTicketVersion",summary.path("ticketVersion").asInt(),"items",quantities),token); }
    static void assignAndAccept(Purchase p) throws Exception { ok(p.ticketId(),"assign",hq,Map.of("engineerId",engineerId1)); ok(p.ticketId(),"accept",engineer1,Map.of()); }
    static void repairResult(Purchase p) throws Exception {
        ok(p.ticketId(),"start",engineer1,Map.of()); String image=upload(engineer1,"RESULT",null,p.ticketId(),UUID.randomUUID().toString()).body().path("id").asText();
        ok(p.ticketId(),"submit-result",engineer1,Map.of("resultNote","材料安装完成，已验证运行稳定。","attachmentIds",List.of(image),"replacementSerialNumber","NEW-SYNTHETIC-0001"));
    }
    @Test @EnabledIfSystemProperty(named="repair.browser",matches="true") void browserPartsAndReplacement() throws Exception {
        String parts=report(asset("UI-PARTS").path("id").asText(),"浏览器配件维修").path("ticketId").asText();
        String replacement=report(asset("UI-REPLACE").path("id").asText(),"浏览器替换设备").path("ticketId").asText();
        java.nio.file.Path output=java.nio.file.Path.of("target/repair-procurement-browser"); java.nio.file.Files.createDirectories(output); java.nio.file.Files.write(output.resolve("sample.png"),picture());
        RepairBrowserSupport.run("repair-procurement-browser-test.cjs",output.resolve("browser.log"),base,parts,replacement);
        assertThat(detail(parts,hq).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail(replacement,hq).path("replacementAssetId").asText()).isNotEqualTo("null");
    }
    @Test void partsOrderRequiresArrivalAndKeepsPricesHqOnly() throws Exception {
        Purchase p=purchase("PART_REPAIR"); var saved=save(p,List.of(item("PART",3,"19.90",true)),"-4.70"); assertThat(saved.status()).isEqualTo(200); assertThat(saved.body().path("confirmedTotal").asText()).isEqualTo("55.00");
        place(p); assignAndAccept(p);
        assertThat(command(p.ticketId(),"start",engineer1,Map.of()).body().path("code").asText()).isEqualTo("MATERIALS_NOT_READY");
        assertThat(request("GET","/api/v1/repair/purchase-orders/"+p.orderId(),null,managerA,null).status()).isEqualTo(403);
        assertThat(request("GET","/api/v1/repair/purchase-orders/"+p.orderId(),null,managerB,null).status()).isEqualTo(404);
        var safe=materials(p,engineer1); assertThat(safe.toString()).doesNotContain("unitPrice","supplier","confirmedTotal","SYNTHETIC-ORDER");
        String itemId=safe.path("items").get(0).path("id").asText();
        assertThat(receive(p,managerA,List.of(Map.of("purchaseItemId",itemId,"quantity",1))).status()).isEqualTo(200);
        assertThat(materials(p,managerA).path("status").asText()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(command(p.ticketId(),"start",engineer1,Map.of()).body().path("code").asText()).isEqualTo("MATERIALS_NOT_READY");
        assertThat(receive(p,managerA,List.of(Map.of("purchaseItemId",itemId,"quantity",3))).body().path("code").asText()).isEqualTo("MATERIAL_OVER_RECEIPT");
        assertThat(receive(p,engineer1,List.of(Map.of("purchaseItemId",itemId,"quantity",2))).status()).isEqualTo(200);
        repairResult(p); ok(p.ticketId(),"confirm",managerA,Map.of());
        assertThat(db.queryForObject("SELECT model FROM repair_assets WHERE id=?",String.class,p.assetId())).isEqualTo("OLD-MODEL-01");
        assertThat(detail(p.ticketId(),hq).path("costStatus").asText()).isEqualTo("PENDING");
        var costs=write("PUT","/api/v1/repair/tickets/"+p.ticketId()+"/costs",Map.of("expectedVersion",detail(p.ticketId(),hq).path("version").asInt(),"laborAmount","125.00","otherAmount","10.00","note","总部核对确认"),hq);
        assertThat(costs.status()).isEqualTo(200); assertThat(costs.body().path("totalAmount").asText()).isEqualTo("190.00");
        assertThat(request("GET","/api/v1/repair/tickets/"+p.ticketId()+"/costs",null,managerA,null).status()).isEqualTo(403);
        assertThat(request("GET","/api/v1/repair/tickets/"+p.ticketId()+"/events",null,managerA,null).body().toString()).doesNotContain("总部保存采购草稿","总部已确认维修费用","125.00");
    }
    @Test void replacementAcceptanceCreatesOneNewAssetAndPreservesOriginal() throws Exception {
        Purchase p=purchase("REPLACEMENT"); assertThat(save(p,List.of(item("REPLACEMENT",1,"2599.00",true)),"20.50").status()).isEqualTo(200); place(p);
        String itemId=materials(p,managerA).path("items").get(0).path("id").asText(); assertThat(receive(p,managerA,List.of(Map.of("purchaseItemId",itemId,"quantity",1))).status()).isEqualTo(200);
        assignAndAccept(p); repairResult(p);
        var body=Map.of("expectedVersion",detail(p.ticketId(),managerA).path("version").asInt()); String key=UUID.randomUUID().toString(),path="/api/v1/repair/tickets/"+p.ticketId()+"/confirm";
        var first=request("POST",path,body,managerA,key); var replay=request("POST",path,body,managerA,key); assertThat(first.status()).describedAs(first.body().toString()).isEqualTo(200); assertThat(replay.body()).isEqualTo(first.body());
        String newId=detail(p.ticketId(),hq).path("replacementAssetId").asText(); assertThat(newId).isNotEqualTo("null").isNotEqualTo(p.assetId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_assets WHERE source_purchase_item_id=?",Long.class,itemId)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT lifecycle FROM repair_assets WHERE id=?",String.class,p.assetId())).isEqualTo("RETIRED");
        assertThat(db.queryForObject("SELECT model FROM repair_assets WHERE id=?",String.class,p.assetId())).isEqualTo("OLD-MODEL-01");
        assertThat(db.queryForObject("SELECT model FROM repair_assets WHERE id=?",String.class,newId)).isEqualTo("NEW-MODEL-88");
        assertThat(db.queryForObject("SELECT serial_number FROM repair_assets WHERE id=?",String.class,newId)).isEqualTo("NEW-SYNTHETIC-0001");
        assertThat(detail(p.ticketId(),managerA).path("facilitySnapshot").path("model").asText()).isEqualTo("OLD-MODEL-01");
    }
    @Test void orderScreenshotPrivateAndCancelledHistoryAllowsNewDraft() throws Exception {
        Purchase p=purchase("PART_REPAIR");
        var uploaded=upload(hq,"ORDER_SCREENSHOT",null,null,UUID.randomUUID().toString(),p.orderId()); assertThat(uploaded.status()).isEqualTo(201);
        String id=uploaded.body().path("id").asText(),key=UUID.randomUUID().toString(); var body=versions(p);
        assertThat(request("POST","/api/v1/repair/attachments/"+id+"/bind-order",body,hq,key).status()).isEqualTo(200);
        assertThat(request("POST","/api/v1/repair/attachments/"+id+"/bind-order",body,hq,key).status()).isEqualTo(200);
        assertThat(imageStatus(id,hq)).isEqualTo(200); assertThat(imageStatus(id,managerA)).isEqualTo(403);
        var cancel=versions(p); cancel.put("reason","供应商缺货，保留原记录后重新采购");
        assertThat(write("POST","/api/v1/repair/purchase-orders/"+p.orderId()+"/cancel",cancel,hq).status()).isEqualTo(200);
        var created=write("POST","/api/v1/repair/tickets/"+p.ticketId()+"/purchase-orders",Map.of("expectedTicketVersion",detail(p.ticketId(),hq).path("version").asInt()),hq);
        assertThat(created.status()).isEqualTo(201);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_purchase_orders WHERE ticket_id=?",Long.class,p.ticketId())).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_purchase_orders WHERE ticket_id=? AND active_slot=1",Long.class,p.ticketId())).isEqualTo(1);
    }
    @Test void concurrentReceiptsNeverExceedOrderedQuantity() throws Exception {
        Purchase p=purchase("PART_REPAIR"); assertThat(save(p,List.of(item("PART",1,"100.00",true)),"0.00").status()).isEqualTo(200); place(p); assignAndAccept(p);
        var summary=materials(p,managerA); String itemId=summary.path("items").get(0).path("id").asText();
        var body=Map.of("expectedVersion",summary.path("purchaseOrderVersion").asInt(),"expectedTicketVersion",summary.path("ticketVersion").asInt(),"items",List.of(Map.of("purchaseItemId",itemId,"quantity",1)));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->write("POST","/api/v1/repair/purchase-orders/"+p.orderId()+"/receipts",body,managerA));
            var second=pool.submit(()->write("POST","/api/v1/repair/purchase-orders/"+p.orderId()+"/receipts",body,engineer1));
            assertThat(List.of(first.get(15,TimeUnit.SECONDS).status(),second.get(15,TimeUnit.SECONDS).status())).containsExactlyInAnyOrder(200,409);
        } finally { pool.shutdownNow(); }
        assertThat(db.queryForObject("SELECT received_quantity FROM repair_purchase_items WHERE id=?",Integer.class,itemId)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_material_receipts WHERE purchase_order_id=?",Long.class,p.orderId())).isEqualTo(1);
    }
    @Test void multiItemOverReceiptRollsBackEntireAttempt() throws Exception {
        Purchase p=purchase("PART_REPAIR"); assertThat(save(p,List.of(item("PART",2,"25.00",true),item("PART",1,"15.00",true)),"0.00").status()).isEqualTo(200); place(p);
        var lines=materials(p,managerA).path("items"); String one=lines.get(0).path("id").asText(),two=lines.get(1).path("id").asText();
        assertThat(receive(p,managerA,List.of(Map.of("purchaseItemId",one,"quantity",1),Map.of("purchaseItemId",two,"quantity",2))).status()).isEqualTo(409);
        assertThat(db.queryForObject("SELECT SUM(received_quantity) FROM repair_purchase_items WHERE purchase_order_id=?",Integer.class,p.orderId())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_material_receipts WHERE purchase_order_id=?",Long.class,p.orderId())).isZero();
    }
    @Test void humanConfirmationReplacementRulesAndNonnegativeTotalsAreRequired() throws Exception {
        Purchase p=purchase("REPLACEMENT"); assertThat(save(p,List.of(item("PART",1,"10.00",false)),"0.00").status()).isEqualTo(200);
        assertThat(write("POST","/api/v1/repair/purchase-orders/"+p.orderId()+"/place",versions(p),hq).status()).isEqualTo(422);
        assertThat(save(p,List.of(item("REPLACEMENT",2,"10.00",true)),"0.00").status()).isEqualTo(200);
        assertThat(write("POST","/api/v1/repair/purchase-orders/"+p.orderId()+"/place",versions(p),hq).status()).isEqualTo(422);
        int version=order(p).path("version").asInt(); assertThat(save(p,List.of(item("REPLACEMENT",1,"10.00",true)),"-11.00").status()).isEqualTo(422);
        assertThat(order(p).path("version").asInt()).isEqualTo(version);
    }
    @Test void hqTicketCancellationCancelsOnlyUnorderedDraft() throws Exception {
        Purchase p=purchase("PART_REPAIR"); ok(p.ticketId(),"cancel",hq,Map.of("reason","门店维修计划取消"));
        assertThat(order(p).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("SELECT health_status FROM repair_assets WHERE id=?",String.class,p.assetId())).isEqualTo("FAULTY");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_active_assets WHERE asset_id=?",Long.class,p.assetId())).isZero();
    }
}
