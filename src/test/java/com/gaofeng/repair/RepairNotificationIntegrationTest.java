package com.gaofeng.repair;
import com.fasterxml.jackson.databind.JsonNode;
import com.gaofeng.repair.attachment.service.*;
import com.gaofeng.repair.notification.mapper.NotificationMapper;
import com.gaofeng.repair.notification.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.gaofeng.repair.RepairIdentityIntegrationTest.*;
import static com.gaofeng.repair.RepairWorkflowIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="repair.integration",matches="true")
class RepairNotificationIntegrationTest {
    @TestConfiguration static class MailConfiguration {
        @Bean @Primary org.springframework.mail.javamail.JavaMailSender fakeMailSender(){return org.mockito.Mockito.mock(org.springframework.mail.javamail.JavaMailSender.class);}
    }
    static NotificationMapper notifications;static org.springframework.mail.javamail.JavaMailSender mail;
    static List<Long> legacyJobs;static String legacyDatabase;
    @BeforeAll static void prepareNotifications() throws Exception {
        // An old environment flag must not re-enable business mail. Seed V007 jobs to verify the V008 upgrade.
        prepareWith(Map.of("repair.mail.enabled",true),MailConfiguration.class);
        RepairWorkflowIntegrationTest.seed();notifications=app.getBean(NotificationMapper.class);mail=app.getBean(org.springframework.mail.javamail.JavaMailSender.class);
        ticket();ticket();ticket();ticket();
        legacyJobs=db.queryForList("SELECT id FROM repair_notifications ORDER BY id LIMIT 4",Long.class);
        assertThat(legacyJobs).hasSize(4);
        // Exercise V007 -> latest on its own real temporary DB; the current app must run on its current schema.
        legacyDatabase="repair_test_"+UUID.randomUUID().toString().replace("-","");
        server.createStatement().execute("CREATE DATABASE "+legacyDatabase+" CHARACTER SET utf8mb4");
        var environment=app.getEnvironment();
        var legacy=org.flywaydb.core.Flyway.configure().configuration(app.getBean(org.flywaydb.core.Flyway.class).getConfiguration())
                .dataSource(environment.getRequiredProperty("spring.datasource.url").replace(database,legacyDatabase),environment.getRequiredProperty("spring.datasource.username"),environment.getRequiredProperty("spring.datasource.password")).target("7").load();
        legacy.migrate();
        for(String table:List.of("users","repair_members","repair_stores","repair_member_stores","repair_categories","repair_assets","repair_tickets","repair_ticket_events","repair_notifications"))
            db.update("INSERT INTO "+legacyDatabase+"."+table+" SELECT * FROM "+table);
        String[] states={"QUEUED","RUNNING","FAILED","SUCCEEDED"};
        for(int i=0;i<4;i++)db.update("INSERT INTO "+legacyDatabase+".repair_delivery_jobs(notification_id,channel,status) VALUES(?,'EMAIL',?)",legacyJobs.get(i),states[i]);
        org.flywaydb.core.Flyway.configure().configuration(legacy.getConfiguration()).target(org.flywaydb.core.api.MigrationVersion.LATEST).load().migrate();
    }
    @AfterAll static void closeNotifications() throws Exception {
        try{if(legacyDatabase!=null && legacyDatabase.matches("repair_test_[a-f0-9]{32}")) server.createStatement().execute("DROP DATABASE "+legacyDatabase);}
        finally{RepairWorkflowIntegrationTest.close();}
    }
    @BeforeEach void resetMail(){org.mockito.Mockito.clearInvocations(mail);}
    static String ticket()throws Exception{return report(asset("NOTICE-"+UUID.randomUUID().toString().substring(0,8)).path("id").asText(),"通知验证工单").path("ticketId").asText();}
    static JsonNode feed(String token)throws Exception{var response=request("GET","/api/v1/repair/notifications?page=1&size=100",null,token,null);assertThat(response.status()).describedAs(response.body().toString()).isEqualTo(200);return response.body();}
    @Test void ownFeedReadIsMonotonicAndOtherStoresCannotSeeNotifications()throws Exception{
        String id=ticket();var mine=feed(hq);String notice=null;for(var row:mine.path("items"))if(row.path("ticketId").asText().equals(id))notice=row.path("id").asText();assertThat(notice).isNotNull();
        assertThat(feed(managerB).toString()).doesNotContain("\"ticketId\":\""+id+"\"");String key=UUID.randomUUID().toString();var body=Map.of("ids",List.of(notice));
        var read=request("POST","/api/v1/repair/notifications/read",body,hq,key);var replay=request("POST","/api/v1/repair/notifications/read",body,hq,key);assertThat(read.status()).isEqualTo(200);assertThat(replay.body()).isEqualTo(read.body());
        assertThat(write("POST","/api/v1/repair/notifications/read",body,managerB).body().path("updated").asInt()).isZero();assertThat(db.queryForObject("SELECT is_read FROM repair_notifications WHERE id=?",Boolean.class,notice)).isTrue();
    }
    @Test void formerEngineerOnlyRetainsGenericRemovalNotice()throws Exception{
        String id=ticket();ok(id,"approve",hq,Map.of("procurementRequired",false,"resolutionPlan","REPAIR","priority","NORMAL"));ok(id,"assign",hq,Map.of("engineerId",engineerId1));ok(id,"accept",engineer1,Map.of());ok(id,"start",engineer1,Map.of());ok(id,"reassign",hq,Map.of("engineerId",engineerId2,"reason","调整工程师"));
        long oldUser=Long.parseLong(engineerId1);assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_notifications WHERE recipient_id=? AND ticket_id=?",Long.class,oldUser,id)).isGreaterThan(1);
        var rows=feed(engineer1).path("items");boolean found=false;for(var row:rows)if(row.path("type").asText().equals("ASSIGNMENT_REMOVED")){found=true;assertThat(row.path("ticketId").isNull()).isTrue();}
        assertThat(found).isTrue();assertThat(rows.toString()).doesNotContain("安排工程师","调整工程师");
    }
    @Test void businessEventsOnlyCreateInAppNotifications()throws Exception{
        long jobs=db.queryForObject("SELECT COUNT(*) FROM repair_delivery_jobs",Long.class);
        String id=ticket();ok(id,"approve",hq,Map.of("procurementRequired",false,"resolutionPlan","REPAIR","priority","NORMAL"));
        ok(id,"assign",hq,Map.of("engineerId",engineerId1));ok(id,"accept",engineer1,Map.of());
        assertThat(detail(id,hq).path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(feed(engineer1).toString()).contains(id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_delivery_jobs",Long.class)).isEqualTo(jobs);
        org.mockito.Mockito.verifyNoInteractions(mail);
        assertThat(Arrays.stream(app.getBeanDefinitionNames()).map(app::getType).filter(Objects::nonNull).map(Class::getSimpleName))
            .doesNotContain("DeliveryWorker","SmtpRepairMailClient");
    }
    @Test void upgradeCancelsPendingBusinessMailAndRetryEndpointsAreRemoved()throws Exception{
        for(int i=0;i<3;i++){
            assertThat(db.queryForObject("SELECT status FROM "+legacyDatabase+".repair_delivery_jobs WHERE notification_id=?",String.class,legacyJobs.get(i))).isEqualTo("CANCELLED");
            assertThat(db.queryForObject("SELECT last_error_code FROM "+legacyDatabase+".repair_delivery_jobs WHERE notification_id=?",String.class,legacyJobs.get(i))).isEqualTo("NOTIFICATION_IN_APP_ONLY");
        }
        assertThat(db.queryForObject("SELECT status FROM "+legacyDatabase+".repair_delivery_jobs WHERE notification_id=?",String.class,legacyJobs.get(3))).isEqualTo("SUCCEEDED");
        assertThat(request("GET","/api/v1/repair/delivery-jobs/failed",null,hq,null).status()).isEqualTo(404);
        assertThat(write("POST","/api/v1/repair/delivery-jobs/1/retry",Map.of(),hq).status()).isEqualTo(404);
        org.mockito.Mockito.verifyNoInteractions(mail);
    }
    @Test void repeatedConcurrentSlaScanMarksOnceWithoutChangingState()throws Exception{
        String id=ticket();db.update("UPDATE repair_tickets SET submitted_at=UTC_TIMESTAMP(3)-INTERVAL 5 HOUR WHERE id=?",id);var scanner=app.getBean(SlaScanner.class);int version=detail(id,hq).path("version").asInt();ExecutorService pool=Executors.newFixedThreadPool(2);
        try{var first=pool.submit(()->scanner.markOne(Long.parseLong(id)));var second=pool.submit(()->scanner.markOne(Long.parseLong(id)));assertThat(first.get(15,TimeUnit.SECONDS)+second.get(15,TimeUnit.SECONDS)).isEqualTo(1);}finally{pool.shutdownNow();}
        assertThat(scanner.markOne(Long.parseLong(id))).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_sla_marks WHERE ticket_id=?",Long.class,id)).isEqualTo(1);assertThat(detail(id,hq).path("version").asInt()).isEqualTo(version);
        assertThat(request("GET","/api/v1/repair/dashboard",null,hq,null).body().path("overdueCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(feed(managerB).toString()).doesNotContain("SLA_REVIEW");
    }
    @Test void privateCleanupDeletesOnlyExpiredStagedAndOldUnreferencedFiles()throws Exception{
        var storage=app.getBean(AttachmentService.class);var janitor=app.getBean(AttachmentJanitor.class);String staged=upload(managerA,"FAULT",storeA,null,UUID.randomUUID().toString()).body().path("id").asText();
        Path stagedPath=storage.privateRoot().resolve(db.queryForObject("SELECT object_key FROM repair_attachments WHERE id=?",String.class,staged));db.update("UPDATE repair_attachments SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE id=?",staged);
        Path orphan=storage.privateRoot().resolve(UUID.randomUUID()+".png"),sentinel=storage.privateRoot().resolve("keep-me.txt");Files.write(orphan,picture());Files.writeString(sentinel,"untouched");Files.setLastModifiedTime(orphan,FileTime.from(Instant.now().minusSeconds(49*3600)));
        assertThat(janitor.cleanup()).isGreaterThanOrEqualTo(2);assertThat(Files.exists(stagedPath)).isFalse();assertThat(Files.exists(orphan)).isFalse();assertThat(Files.readString(sentinel)).isEqualTo("untouched");assertThat(db.queryForObject("SELECT status FROM repair_attachments WHERE id=?",String.class,staged)).isEqualTo("DELETED");
    }
}

