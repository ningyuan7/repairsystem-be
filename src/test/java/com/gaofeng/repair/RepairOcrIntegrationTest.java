package com.gaofeng.repair;
import com.fasterxml.jackson.databind.JsonNode;
import com.gaofeng.repair.ocr.api.dto.OcrDtos.*;
import com.gaofeng.repair.ocr.domain.OcrJob;
import com.gaofeng.repair.ocr.mapper.OcrMapper;
import com.gaofeng.repair.ocr.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.gaofeng.repair.RepairIdentityIntegrationTest.*;
import static com.gaofeng.repair.RepairWorkflowIntegrationTest.*;
import static com.gaofeng.repair.RepairProcurementIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="repair.integration",matches="true")
class RepairOcrIntegrationTest {
    @TestConfiguration static class FakeConfiguration { @Bean @Primary FakeOcrClient fakeOcrClient() { return new FakeOcrClient(); } }
    static final class FakeOcrClient implements OrderOcrClient {
        boolean configured=true; String failure; boolean transientFailure; AtomicInteger calls=new AtomicInteger();
        public boolean configured() { return configured; } public String provider() { return "FAKE_TEST_ONLY"; }
        public Recognition recognize(byte[] image) { calls.incrementAndGet(); if(failure!=null) throw new OcrFailure(failure,transientFailure,"synthetic-error"); return OrderFieldExtractorTest.sample("订单号: OCR-SYNTHETIC","商家: OCR合成供应商","商品: 冷柜温控器 品牌: OCR品牌 型号: OCR-T26 数量: 2 单价: 39.90","商品: 安装支架 数量: 1 单价: 10.00","合计: 89.80"); }
    }
    static FakeOcrClient fake; static OcrWorker worker; static OcrMapper jobs;
    @BeforeAll static void setupOcr() throws Exception { prepareWith(Map.of("repair.ocr.per-minute",1000,"repair.ocr.per-day",1000),FakeConfiguration.class); RepairWorkflowIntegrationTest.seed(); fake=app.getBean(FakeOcrClient.class); worker=app.getBean(OcrWorker.class); jobs=app.getBean(OcrMapper.class); }
    @AfterAll static void cleanupOcr() throws Exception { RepairWorkflowIntegrationTest.close(); }
    @BeforeEach void reset() { fake.configured=true; fake.failure=null; fake.transientFailure=false; }
    record Source(Purchase purchase,String attachmentId) {}
    static Source source() throws Exception {
        Purchase p=purchase("PART_REPAIR"); String id=upload(hq,"ORDER_SCREENSHOT",null,null,UUID.randomUUID().toString(),p.orderId()).body().path("id").asText();
        assertThat(write("POST","/api/v1/repair/attachments/"+id+"/bind-order",versions(p),hq).status()).isEqualTo(200); return new Source(p,id);
    }
    static Result createJob(Source source) throws Exception { return write("POST","/api/v1/repair/purchase-orders/"+source.purchase.orderId()+"/ocr-jobs",Map.of("attachmentId",source.attachmentId),hq); }
    static String enqueue(Source source) throws Exception { var result=createJob(source); assertThat(result.status()).describedAs(result.body().toString()).isEqualTo(202); return result.body().path("jobId").asText(); }
    static void due(String id) { db.update("UPDATE repair_ocr_jobs SET next_run_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",id); }
    @Test void candidatesRemainSuggestionsAndSaveChecksVersionsAndSource() throws Exception {
        Source source=source(); String path="/api/v1/repair/purchase-orders/"+source.purchase.orderId()+"/ocr-jobs", key=UUID.randomUUID().toString(); var body=Map.of("attachmentId",source.attachmentId);
        Result created=request("POST",path,body,hq,key),replay=request("POST",path,body,hq,key); assertThat(created.status()).isEqualTo(202); assertThat(replay.body()).isEqualTo(created.body());
        String id=created.body().path("jobId").asText(); assertThat(worker.runOnce()).isTrue(); var result=request("GET","/api/v1/repair/ocr-jobs/"+id,null,hq,null);
        assertThat(result.body().path("status").asText()).isEqualTo("SUCCEEDED"); assertThat(result.body().path("result").path("candidates").path("items")).hasSize(2);
        assertThat(order(source.purchase).path("items")).isEmpty(); assertThat(request("GET","/api/v1/repair/ocr-jobs/"+id,null,managerA,null).status()).isEqualTo(403);
        var draft=versions(source.purchase); draft.putAll(Map.of("supplier","人工核对供应商","currency","CNY","adjustmentAmount","0.00","sourceOcrJobId",id));
        var item=item("PART",2,"39.90",true); item.put("sourceCandidateIndex",0); draft.put("items",List.of(item));
        assertThat(write("PUT","/api/v1/repair/purchase-orders/"+source.purchase.orderId(),draft,hq).status()).isEqualTo(200);
        assertThat(write("PUT","/api/v1/repair/purchase-orders/"+source.purchase.orderId(),draft,hq).body().path("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(order(source.purchase).path("items").get(0).path("sourceOcrJobId").asText()).isEqualTo(id);
        Source other=source(); var invalid=versions(other.purchase); invalid.putAll(draft); invalid.putAll(versions(other.purchase));
        assertThat(write("PUT","/api/v1/repair/purchase-orders/"+other.purchase.orderId(),invalid,hq).status()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT model FROM repair_assets WHERE id=?",String.class,source.purchase.assetId())).isEqualTo("OLD-MODEL-01");
    }
    @Test void expiredLeaseRecoversAndLateWorkerCannotWrite() throws Exception {
        Source source=source(); String id=enqueue(source); String oldToken=UUID.randomUUID().toString();
        db.update("UPDATE repair_ocr_jobs SET status='RUNNING',attempts=1,lease_token=?,lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",oldToken,id); OcrJob old=jobs.get(id);
        assertThat(worker.runOnce()).isTrue(); assertThat(jobs.get(id).getStatus()).isEqualTo("SUCCEEDED"); assertThat(jobs.get(id).getAttempts()).isEqualTo(2);
        assertThat(worker.finish(old,"FAILED",null,"LATE_RESPONSE",null)).isZero(); assertThat(jobs.get(id).getErrorCode()).isNull();
        String exhausted=enqueue(source); db.update("UPDATE repair_ocr_jobs SET status='RUNNING',attempts=2,lease_token=?,lease_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",oldToken,exhausted); worker.runOnce();
        assertThat(jobs.get(exhausted).getStatus()).isEqualTo("FAILED"); assertThat(jobs.get(exhausted).getAttempts()).isEqualTo(2);
    }
    @Test void transientFailuresRetryTwicePermanentFailuresDoNotAndManualRetryCreatesNewTask() throws Exception {
        Source source=source(); fake.failure="TEMPORARY"; fake.transientFailure=true; String id=enqueue(source);
        worker.runOnce(); assertThat(jobs.get(id).getStatus()).isEqualTo("QUEUED"); assertThat(jobs.get(id).getAttempts()).isEqualTo(1); due(id); worker.runOnce(); assertThat(jobs.get(id).getStatus()).isEqualTo("FAILED");
        var retry=write("POST","/api/v1/repair/ocr-jobs/"+id+"/retry",Map.of("expectedVersion",jobs.get(id).getVersion()),hq); assertThat(retry.status()).isEqualTo(202); assertThat(retry.body().path("jobId").asText()).isNotEqualTo(id);
        fake.transientFailure=false; worker.runOnce(); assertThat(jobs.get(retry.body().path("jobId").asText()).getStatus()).isEqualTo("FAILED");
    }
    @Test void disabledConfigurationAndUnboundImagesDoNotCreateJobs() throws Exception {
        Source source=source(); fake.configured=false; assertThat(createJob(source).body().path("code").asText()).isEqualTo("OCR_NOT_CONFIGURED");
        assertThat(jobs.forOrder(Long.parseLong(source.purchase.orderId()))).isEmpty(); fake.configured=true;
        String staged=upload(hq,"ORDER_SCREENSHOT",null,null,UUID.randomUUID().toString(),source.purchase.orderId()).body().path("id").asText();
        assertThat(write("POST","/api/v1/repair/purchase-orders/"+source.purchase.orderId()+"/ocr-jobs",Map.of("attachmentId",staged),hq).status()).isEqualTo(400);
    }
    @Test void concurrentWorkersClaimOneJobOnce() throws Exception {
        Source source=source(); String id=enqueue(source); int before=fake.calls.get(); ExecutorService pool=Executors.newFixedThreadPool(2);
        try { var a=pool.submit(()->worker.runOnce()); var b=pool.submit(()->worker.runOnce()); assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false); } finally { pool.shutdownNow(); }
        assertThat(fake.calls.get()-before).isEqualTo(1); assertThat(jobs.get(id).getStatus()).isEqualTo("SUCCEEDED");
    }
    @Test void orderPlacedOrRequesterDisabledCancelsBeforeExternalCall() throws Exception {
        Source source=source(); String id=enqueue(source); save(source.purchase,List.of(item("PART",1,"10.00",true)),"0.00"); place(source.purchase); int before=fake.calls.get(); worker.runOnce(); assertThat(jobs.get(id).getStatus()).isEqualTo("CANCELLED"); assertThat(fake.calls.get()).isEqualTo(before);
        inviteAndActivate("ocr-hq@repair.example","ENGINEER",List.of()); String memberId=login("ocr-hq@repair.example").body().path("user").path("id").asText();
        assertThat(write("PUT","/api/v1/repair/members/"+memberId+"/access",Map.of("expectedVersion",0,"role","HQ_ADMIN","storeIds",List.of(),"reason","OCR权限测试"),hq).status()).isEqualTo(200);
        String other=login("ocr-hq@repair.example").body().path("token").path("accessToken").asText(); Source next=source();
        var created=write("POST","/api/v1/repair/purchase-orders/"+next.purchase.orderId()+"/ocr-jobs",Map.of("attachmentId",next.attachmentId),other); assertThat(created.status()).isEqualTo(202);
        db.update("UPDATE repair_members SET status='DISABLED',auth_version=auth_version+1 WHERE user_id=(SELECT id FROM users WHERE email='ocr-hq@repair.example')");
        worker.runOnce(); assertThat(jobs.get(created.body().path("jobId").asText()).getStatus()).isEqualTo("CANCELLED"); assertThat(fake.calls.get()).isEqualTo(before);
    }
    @Test @EnabledIfSystemProperty(named="repair.browser",matches="true") void browserOcrCandidates() throws Exception {
        Source source=source(); java.nio.file.Path output=java.nio.file.Path.of("target/repair-ocr-browser"); java.nio.file.Files.createDirectories(output);
        org.springframework.test.util.ReflectionTestUtils.setField(worker,"enabled",true);
        try {
            RepairBrowserSupport.run("repair-ocr-browser-test.cjs",output.resolve("browser.log"),base,source.purchase.orderId());
            JsonNode item=order(source.purchase).path("items").get(0); assertThat(item.path("sourceOcrJobId").asText()).isNotEqualTo("null"); assertThat(item.path("model").asText()).isEqualTo("OCR-T26-人工校对");
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(worker,"enabled",false); }
    }
}
