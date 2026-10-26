package com.gaofeng.repair;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static com.gaofeng.repair.RepairIdentityIntegrationTest.*;
import static com.gaofeng.repair.RepairWorkflowIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named="repair.integration",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepairDispatchIntegrationTest {
    static String assigned,accepted,working,waiting,completed,disabledId;
    static final String ROOT="/api/v1/repair/dashboard/engineer-dispatch";
    @BeforeAll static void prepareDispatch()throws Exception{
        RepairWorkflowIntegrationTest.setup();
        db.update("UPDATE repair_members SET display_name='陈工',contact_phone='13000000003' WHERE user_id=?",engineerId1);
        db.update("UPDATE repair_members SET display_name='刘工（制冷设备与门店电气维护）' WHERE user_id=?",engineerId2);
        inviteAndActivate("disabled@dispatch.example","ENGINEER",List.of());
        disabledId=login("disabled@dispatch.example").body().path("user").path("id").asText();
        db.update("UPDATE repair_members SET display_name='已停用工程师' WHERE user_id=?",disabledId);
        assertThat(write("POST","/api/v1/repair/members/"+disabledId+"/disable",Map.of("expectedVersion",0,"reason","测试停用展示"),hq).status()).isEqualTo(200);
        assigned=assign("待接单：冷柜温控器需要排查");
        accepted=assign("已接单：配电柜检查");ok(accepted,"accept",engineer1,Map.of());
        working=report(null,"维修中：门店后厨冷柜持续高温，请检查温控器与线路连接").path("ticketId").asText();start(working);
        waiting=report(null,"待验收：照明设备维修").path("ticketId").asText();start(waiting);submit(waiting);
        completed=report(null,"已完成任务不进入当前派单").path("ticketId").asText();start(completed);submit(completed);ok(completed,"confirm",managerA,Map.of());
        report(null,"未派单工单不计入工程师负载");
    }
    @AfterAll static void closeDispatch()throws Exception{RepairWorkflowIntegrationTest.close();}
    static String assign(String title)throws Exception{String id=report(null,title).path("ticketId").asText();ok(id,"approve",hq,Map.of("procurementRequired",false,"resolutionPlan","REPAIR","priority","NORMAL"));ok(id,"assign",hq,Map.of("engineerId",engineerId1));return id;}
    static void submit(String id)throws Exception{String image=upload(engineer1,"RESULT",null,id,UUID.randomUUID().toString()).body().path("id").asText();ok(id,"submit-result",engineer1,Map.of("resultNote","检查运行正常，等待门店验收","attachmentIds",List.of(image)));}
    static JsonNode get(String path)throws Exception{var result=request("GET",path,null,hq,null);assertThat(result.status()).describedAs(result.body().toString()).isEqualTo(200);return result.body();}
    static JsonNode engineer(String id)throws Exception{for(var row:get(ROOT).path("items"))if(row.path("id").asText().equals(id))return row;throw new AssertionError("Engineer missing");}
    @Test @Order(1) void summaryIncludesIdleAndDisabledEngineersWithoutCountingClosedOrUnassignedTickets()throws Exception{
        var result=get(ROOT);assertThat(result.path("total").asInt()).isEqualTo(3);
        var one=result.path("items").get(0);assertThat(one.path("id").asText()).isEqualTo(engineerId1);
        for(String state:List.of("assigned","accepted","inProgress","waitingAcceptance"))assertThat(one.path(state).asInt()).describedAs(state).isEqualTo(1);
        assertThat(one.path("currentOpen").asInt()).isEqualTo(4);assertThat(one.path("contactPhone").asText()).isEqualTo("13000000003");
        assertThat(engineer(engineerId2).path("currentOpen").asInt()).isZero();assertThat(engineer(disabledId).path("memberStatus").asText()).isEqualTo("DISABLED");
        assertThat(get(ROOT+"?page=2&size=1").path("items")).hasSize(1);
    }
    @Test @Order(2) void detailIsPagedAndFiltersExactlyTheCurrentAssignmentStages()throws Exception{
        String path=ROOT+"/"+engineerId1+"/tickets";var all=get(path);assertThat(all.path("total").asInt()).isEqualTo(4);
        assertThat(all.toString()).doesNotContain("已完成任务不进入当前派单");
        var page=get(path+"?status=IN_PROGRESS&page=1&size=1");assertThat(page.path("total").asInt()).isEqualTo(1);assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(working);
        assertThat(page.path("items").get(0).path("assignedAt").isTextual()).isTrue();
        assertThat(get(ROOT+"/"+engineerId2+"/tickets").path("total").asInt()).isZero();
    }
    @Test @Order(3) void onlyHeadquartersCanReadDispatchAndInvalidFiltersAreRejected()throws Exception{
        for(String token:List.of(managerA,engineer1)){
            assertThat(request("GET",ROOT,null,token,null).status()).isEqualTo(403);
            assertThat(request("GET",ROOT+"/"+engineerId1+"/tickets",null,token,null).status()).isEqualTo(403);
        }
        assertThat(request("GET",ROOT+"?size=101",null,hq,null).status()).isEqualTo(400);
        assertThat(request("GET",ROOT+"/"+engineerId1+"/tickets?status=COMPLETED",null,hq,null).status()).isEqualTo(400);
        assertThat(request("GET",ROOT+"/9999999/tickets",null,hq,null).status()).isEqualTo(404);
    }
    @Test @Order(4) @EnabledIfSystemProperty(named="repair.browser",matches="true") void browserDispatchTableAndDrawer()throws Exception{
        RepairBrowserSupport.run("repair-dispatch-browser-test.cjs",Path.of("target/repair-dispatch-browser/browser.log"),base);
    }
    @Test @Order(5) void reassignmentAndWithdrawalImmediatelyMoveTheReadModelWithoutChangingWorkflow()throws Exception{
        assertThat(command(assigned,"reassign",hq,Map.of("engineerId",engineerId2,"reason","待接单状态不可直接改派")).status()).isEqualTo(409);
        ok(working,"reassign",hq,Map.of("engineerId",engineerId2,"reason","调整现场安排"));
        assertThat(engineer(engineerId1).path("currentOpen").asInt()).isEqualTo(3);assertThat(engineer(engineerId2).path("assigned").asInt()).isEqualTo(1);
        assertThat(get(ROOT+"/"+engineerId1+"/tickets").toString()).doesNotContain("维修中：门店后厨冷柜持续高温，请检查温控器与线路连接");
        ok(working,"withdraw-assignment",hq,Map.of("reason","重新协调安排"));
        assertThat(engineer(engineerId2).path("currentOpen").asInt()).isZero();assertThat(detail(working,hq).path("status").asText()).isEqualTo("READY_FOR_DISPATCH");
        ok(waiting,"return",managerA,Map.of("reason","复测仍有问题"));
        var changed=engineer(engineerId1);assertThat(changed.path("inProgress").asInt()).isEqualTo(1);assertThat(changed.path("waitingAcceptance").asInt()).isZero();
    }
}
