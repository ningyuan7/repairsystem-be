package com.gaofeng.repair;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaofeng.repair.bootstrap.RepairApplication;
import com.gaofeng.repair.access.api.dto.AccessDtos.StatusRequest;
import com.gaofeng.repair.access.service.AccessService;
import com.gaofeng.repair.common.RepairException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Creates/drops only repair_test_<uuid>; real local MySQL/Redis, no mail/OCR/provider calls. */
@EnabledIfSystemProperty(named="repair.integration",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepairIdentityIntegrationTest {
    static Connection server;
    static String database;
    static ServletWebServerApplicationContext app;
    static JdbcTemplate db;
    static ObjectMapper json;
    static HttpClient client=HttpClient.newHttpClient();
    static String base,hq,hq2;
    static final String PASSWORD="FixtureOnly3579!";
    record Result(int status,JsonNode body,String requestId) {}
    @BeforeAll static void prepare() throws Exception { prepareWith(Map.of()); }
    @SuppressWarnings("unchecked") static void prepareWith(Map<String,Object> overrides,Class<?>... sources) throws Exception {
        Map<String,Object> config=Map.of();
        Path testConfiguration=Path.of("config/repair-test.yml");
        if(Files.exists(testConfiguration)) try(var stream=Files.newInputStream(testConfiguration)) { config=new Yaml().load(stream); }
        Map<String,Object> spring=(Map<String,Object>)config.getOrDefault("spring",Map.of());
        Map<String,Object> source=new HashMap<>((Map<String,Object>)spring.getOrDefault("datasource",Map.of()));
        source.put("url",System.getenv().getOrDefault("REPAIR_TEST_DB_URL",source.getOrDefault("url","jdbc:mysql://localhost:3306/repair_test").toString()));
        source.put("username",System.getenv().getOrDefault("REPAIR_TEST_DB_USERNAME",source.getOrDefault("username","repair_test").toString()));
        source.put("password",System.getenv().getOrDefault("REPAIR_TEST_DB_PASSWORD",source.getOrDefault("password","").toString()));
        var env=new StandardEnvironment();
        String url=env.resolveRequiredPlaceholders((String)source.get("url"));
        String host=url.substring(0,url.indexOf('/',"jdbc:mysql://".length()));
        String user=env.resolveRequiredPlaceholders((String)source.get("username")),password=env.resolveRequiredPlaceholders(String.valueOf(source.get("password")));
        server=DriverManager.getConnection(host+"/?connectionTimeZone=UTC&connectTimeout=3000&socketTimeout=10000",user,password);
        database="repair_test_"+UUID.randomUUID().toString().replace("-","");
        server.createStatement().execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4");
        Map<String,Object> defaults=new HashMap<>();
        defaults.put("spring.config.name","repair");
        defaults.put("server.port",0);
        defaults.put("spring.datasource.url",host+"/"+database+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=3000&socketTimeout=10000");
        defaults.put("spring.datasource.username",user); defaults.put("spring.datasource.password",password);
        defaults.put("spring.data.redis.database",15);
        defaults.put("repair.storage.local-root",Path.of("target/repair-test-files",database).toAbsolutePath().toString());
        Object data=spring.get("data");
        if(data instanceof Map<?,?> dataMap && dataMap.get("redis") instanceof Map<?,?> redis)
            for(String key:List.of("host","port","password")) if(redis.get(key)!=null) defaults.put("spring.data.redis."+key,env.resolveRequiredPlaceholders(redis.get(key).toString()));
        for(String key:List.of("host","port","password")) {
            String value=System.getenv("REPAIR_TEST_REDIS_"+key.toUpperCase());
            if(value!=null) defaults.put("spring.data.redis."+key,value);
        }
        Path keys=Path.of("target/repair-test-files",database,"keys").toAbsolutePath();
        Files.createDirectories(keys);
        var generator=java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair=generator.generateKeyPair();
        var encoder=Base64.getMimeEncoder(64,new byte[]{10});
        Files.writeString(keys.resolve("private.pem"),"-----BEGIN PRIVATE KEY-----\n"+encoder.encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n");
        Files.writeString(keys.resolve("public.pem"),"-----BEGIN PUBLIC KEY-----\n"+encoder.encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n");
        defaults.put("auth.jwt.private-key",keys.resolve("private.pem").toUri().toString());
        defaults.put("auth.jwt.public-key",keys.resolve("public.pem").toUri().toString());
        defaults.put("repair.bootstrap.enabled",true); defaults.put("repair.bootstrap.email","hq@repair.example");
        defaults.put("repair.bootstrap.password",PASSWORD); defaults.put("repair.bootstrap.name","测试总部");
        defaults.put("auth.password.bcrypt-strength",4);
        defaults.put("repair.cors-origins","http://127.0.0.1:4877");
        defaults.put("app.protection.auth-ip-per-minute",1000); defaults.put("app.protection.login-account-per-ten-minutes",1000);
        defaults.put("repair.workers.enabled",false); defaults.putAll(overrides);
        SpringApplication application=new SpringApplication(RepairApplication.class);
        application.addPrimarySources(Arrays.asList(sources));
        application.setDefaultProperties(defaults);
        // Command-line properties override repair.yml defaults; no credentials are printed.
        String[] arguments=defaults.entrySet().stream().filter(e->!e.getKey().equals("spring.config.name")).map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new);
        app=(ServletWebServerApplicationContext)application.run(arguments);
        base="http://127.0.0.1:"+app.getWebServer().getPort();
        db=app.getBean(JdbcTemplate.class); json=app.getBean(ObjectMapper.class);
        hq=login("hq@repair.example").body.path("token").path("accessToken").asText();
    }
    @AfterAll static void cleanup() throws Exception {
        if(app!=null) app.close();
        if(database!=null && database.matches("repair_test_[a-f0-9]{32}")) {
            Path base=Path.of("target/repair-test-files").toAbsolutePath().normalize(),target=base.resolve(database).normalize();
            if(target.startsWith(base) && Files.isDirectory(target)) try(var files=Files.walk(target)) {
                for(Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        if(server!=null) {
            if(database!=null && database.matches("repair_test_[a-f0-9]{32}")) server.createStatement().execute("DROP DATABASE "+database);
            server.close();
        }
    }
    static Result request(String method,String path,Object body,String token,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json");
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(key!=null) builder.header("Idempotency-Key",key);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(),response.body().isBlank()?json.nullNode():json.readTree(response.body()),response.headers().firstValue("X-Request-Id").orElse(""));
    }
    static Result write(String method,String path,Object body,String token) throws Exception { return request(method,path,body,token,UUID.randomUUID().toString()); }
    static Result login(String email) throws Exception { return request("POST","/api/v1/auth/login",Map.of("identifierType","EMAIL","identifier",email,"password",PASSWORD),null,null); }
    static String createStore(String code) throws Exception {
        Result result=write("POST","/api/v1/repair/stores",Map.of("code",code,"name","测试门店 "+code,"region","华东","address","合成数据地址","status","ACTIVE"),hq);
        assertThat(result.status).isEqualTo(201); assertThat(result.body.path("id").isTextual()).isTrue(); return result.body.path("id").asText();
    }
    static String inviteAndActivate(String email,String role,List<String> stores) throws Exception {
        Result invited=write("POST","/api/v1/repair/invitations",Map.of("email",email,"role",role,"storeIds",stores),hq);
        assertThat(invited.status).isEqualTo(201);
        String token=invited.body.path("activationPath").asText().split("token=")[1];
        Result accepted=request("POST","/api/v1/repair/invitations/accept",Map.of("token",token,"displayName","测试成员","password",PASSWORD),null,null);
        assertThat(accepted.status).isEqualTo(200);
        return token;
    }
    @Test @Order(1) void isolatedApplicationAndRepeatableMigration() throws Exception {
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).startsWith("repair_test_");
        assertThat(db.queryForObject("SELECT @@session.time_zone",String.class)).isEqualTo("+00:00");
        List<String> beanTypes=Arrays.stream(app.getBeanDefinitionNames()).map(app::getType).filter(Objects::nonNull).map(Class::getName).toList();
        assertThat(beanTypes).noneMatch(name->name.startsWith("org.springframework.kafka.") || name.startsWith("co.elastic.clients.") || name.equals("com.gaofeng.auth.api.AuthController"));
        assertThat(app.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
        assertThat(request("GET","/api/v1/repair/session",null,null,null).status).isEqualTo(401);
        assertThat(request("POST","/api/v1/auth/register",Map.of(),null,null).body.path("code").asText()).isEqualTo("REGISTRATION_DISABLED");
        assertThat(request("GET","/api/v1/campus/me",null,hq,null).status).isEqualTo(404);
    }
    @Test @Order(2) void invitationScopeOneTimeUseAndTokenPrivacy() throws Exception {
        String a=createStore("A"),b=createStore("B");
        String token=inviteAndActivate("store@repair.example","STORE_MANAGER",List.of(a));
        String manager=login("store@repair.example").body.path("token").path("accessToken").asText();
        Result stores=request("GET","/api/v1/repair/stores",null,manager,null);
        assertThat(stores.body.path("total").asLong()).isEqualTo(1);
        assertThat(stores.body.path("items").get(0).path("id").asText()).isEqualTo(a);
        assertThat(stores.body.toString()).doesNotContain("\"id\":\""+b+"\"");
        assertThat(request("GET","/api/v1/repair/members",null,manager,null).status).isEqualTo(403);
        assertThat(write("POST","/api/v1/repair/stores",Map.of("code","BAD","name","越权","region","","address","","status","ACTIVE"),manager).status).isEqualTo(403);
        assertThat(request("POST","/api/v1/repair/invitations/accept",Map.of("token",token,"displayName","重复","password",PASSWORD),null,null).status).isEqualTo(410);
        assertThat(db.queryForList("SELECT token_hash FROM repair_invitations",String.class).toString()).doesNotContain(token);
        assertThat(db.queryForList("SELECT response_json FROM repair_idempotency_records",String.class).toString()).doesNotContain(token);
        inviteAndActivate("engineer@repair.example","ENGINEER",List.of());
        String engineer=login("engineer@repair.example").body.path("token").path("accessToken").asText();
        assertThat(request("GET","/api/v1/repair/stores",null,engineer,null).body.path("total").asLong()).isZero();
    }
    @Test @Order(3) void idempotencyReplaysAndRejectsChangedBodyAndUnknownFields() throws Exception {
        String key=UUID.randomUUID().toString();
        Map<String,String> body=Map.of("code","REPLAY","name","幂等门店","region","","address","","status","ACTIVE");
        Result first=request("POST","/api/v1/repair/stores",body,hq,key),second=request("POST","/api/v1/repair/stores",body,hq,key);
        assertThat(first.status).isEqualTo(201); assertThat(second.status).isEqualTo(201); assertThat(second.body).isEqualTo(first.body);
        var changed=new HashMap<>(body); changed.put("name","不同内容");
        assertThat(request("POST","/api/v1/repair/stores",changed,hq,key).body.path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        changed.put("role","HQ_ADMIN"); assertThat(write("POST","/api/v1/repair/stores",changed,hq).status).isEqualTo(400);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_stores WHERE code='REPLAY'",Long.class)).isEqualTo(1);
        assertThat(write("PUT","/api/v1/repair/stores/"+first.body.path("id").asText(),Map.of("code","REPLAY","name","过期版本","region","","address","","status","ACTIVE","expectedVersion",99),hq).body.path("code").asText()).isEqualTo("VERSION_CONFLICT");
        Result error=request("GET","/api/v1/repair/members?page=0",null,hq,null);
        assertThat(error.status).isEqualTo(400); assertThat(error.requestId).isEqualTo(error.body.path("requestId").asText());
    }
    @Test @Order(4) void disabledMemberAndOldGenerationCannotAccessOrRefresh() throws Exception {
        var login=login("engineer@repair.example");
        String accessToken=login.body.path("token").path("accessToken").asText(),refresh=login.body.path("token").path("refreshToken").asText();
        String id=login.body.path("user").path("id").asText();
        assertThat(write("POST","/api/v1/repair/members/"+id+"/disable",Map.of("expectedVersion",0,"reason","测试停用"),hq).status).isEqualTo(200);
        assertThat(request("GET","/api/v1/repair/session",null,accessToken,null).body.path("code").asText()).isEqualTo("MEMBER_DISABLED");
        assertThat(request("POST","/api/v1/auth/token/refresh",Map.of("refreshToken",refresh),null,null).status).isEqualTo(403);
        assertThat(write("POST","/api/v1/repair/members/"+id+"/enable",Map.of("expectedVersion",1,"reason","测试恢复"),hq).status).isEqualTo(200);
        assertThat(request("GET","/api/v1/repair/session",null,accessToken,null).body.path("code").asText()).isEqualTo("SESSION_REVOKED");
        assertThat(request("GET","/api/v1/repair/session",null,refresh,null).status).isEqualTo(401);
        assertThat(request("GET","/api/v1/repair/session",null,login("engineer@repair.example").body.path("token").path("accessToken").asText(),null).status).isEqualTo(200);
    }
    @Test @Order(5) void unavailableMailDoesNotPretendDelivery() throws Exception {
        var denied=request("POST","/api/v1/auth/send-code",Map.of("identifierType","EMAIL","identifier","hq@repair.example","scene","LOGIN"),null,null);
        assertThat(denied.status).isEqualTo(403);assertThat(denied.body.path("code").asText()).isEqualTo("VERIFICATION_SCENE_DISABLED");
        Result result=request("POST","/api/v1/auth/send-code",Map.of("identifierType","EMAIL","identifier","hq@repair.example","scene","RESET_PASSWORD"),null,null);
        assertThat(result.status).isEqualTo(503); assertThat(result.body.path("code").asText()).isEqualTo("MAIL_NOT_CONFIGURED");
    }
    @Test @Order(6) void rollbackRemovesBusinessAuditAndReservation() {
        long stores=db.queryForObject("SELECT COUNT(*) FROM repair_stores",Long.class),audits=db.queryForObject("SELECT COUNT(*) FROM repair_audit_logs",Long.class);
        String key=UUID.randomUUID().toString();
        var idempotency=app.getBean(com.gaofeng.repair.common.IdempotencyService.class);
        assertThatThrownBy(()->idempotency.execute(1L,"rollback",key,Map.of(),200,()->{
            app.getBean(com.gaofeng.repair.access.service.StoreService.class).save(1L,null,new com.gaofeng.repair.access.api.dto.AccessDtos.StoreRequest("ROLLBACK","回滚门店","","",com.gaofeng.repair.access.api.dto.AccessDtos.Status.ACTIVE,null));
            throw new RepairException(409,"TEST_ROLLBACK","合成事务故障");
        })).isInstanceOf(RepairException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_stores",Long.class)).isEqualTo(stores);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_audit_logs",Long.class)).isEqualTo(audits);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_idempotency_records WHERE idempotency_key=?",Long.class,key)).isZero();
    }
    @Test @Order(7) void existingAccountNeedsPasswordProofAndOldInvitationTokenHasNoBusinessAccess() throws Exception {
        var userService=app.getBean(com.gaofeng.user.service.UserService.class);
        var passwordEncoder=app.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
        var existing=userService.createUser(com.gaofeng.user.domain.User.builder().email("existing@repair.example").nickname("已有账号")
                .passwordHash(passwordEncoder.encode(PASSWORD)).tagsJson("[]").build());
        var invited=write("POST","/api/v1/repair/invitations",Map.of("email",existing.getEmail(),"role","ENGINEER","storeIds",List.of()),hq);
        String token=invited.body.path("activationPath").asText().split("token=")[1];
        assertThat(login(existing.getEmail()).status).isEqualTo(401);
        assertThat(request("POST","/api/v1/repair/invitations/accept",Map.of("token",token,"displayName","已有账号"),null,null).body.path("code").asText()).isEqualTo("INVITATION_LOGIN_REQUIRED");
        var proofRequest=HttpRequest.newBuilder(URI.create(base+"/api/v1/auth/login")).header("Content-Type","application/json").header("X-Repair-Invitation",token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("identifierType","EMAIL","identifier",existing.getEmail(),"password",PASSWORD)))).build();
        var proof=client.send(proofRequest,HttpResponse.BodyHandlers.ofString());
        assertThat(proof.statusCode()).isEqualTo(200);
        String accessToken=json.readTree(proof.body()).path("token").path("accessToken").asText();
        assertThat(request("GET","/api/v1/repair/session",null,accessToken,null).status).isEqualTo(403);
        assertThat(request("POST","/api/v1/repair/invitations/accept",Map.of("token",token,"displayName","已有账号"),accessToken,null).status).isEqualTo(200);
        assertThat(request("GET","/api/v1/repair/session",null,accessToken,null).body.path("code").asText()).isEqualTo("SESSION_REVOKED");
        assertThat(login(existing.getEmail()).status).isEqualTo(200);
    }
    @Test @Order(8) void concurrentIdempotentRequestsProduceOneStore() throws Exception {
        String key=UUID.randomUUID().toString();
        var body=Map.of("code","CONCURRENT","name","并发门店","region","","address","","status","ACTIVE");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->request("POST","/api/v1/repair/stores",body,hq,key));
            var second=pool.submit(()->request("POST","/api/v1/repair/stores",body,hq,key));
            var a=first.get(10,TimeUnit.SECONDS); var b=second.get(10,TimeUnit.SECONDS);
            assertThat(a.status).isEqualTo(201); assertThat(b.status).isEqualTo(201); assertThat(a.body).isEqualTo(b.body);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_stores WHERE code='CONCURRENT'",Long.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test @Order(9) void profileCompletionAndCorsContract() throws Exception {
        var login=login("engineer@repair.example");
        String token=login.body.path("token").path("accessToken").asText();
        String id=login.body.path("user").path("id").asText();
        db.update("UPDATE repair_members SET display_name='' WHERE user_id=?",id);
        var before=request("GET","/api/v1/repair/session",null,token,null);
        assertThat(before.body.path("repairProfileComplete").asBoolean()).isFalse();
        var after=write("PATCH","/api/v1/repair/me/profile",Map.of("expectedVersion",before.body.path("memberVersion").asInt(),"displayName","工程师姓名","contactPhone",""),token);
        assertThat(after.status).isEqualTo(200); assertThat(after.body.path("repairProfileComplete").asBoolean()).isTrue();
        var cors=client.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/repair/stores")).header("Origin","http://127.0.0.1:4877")
                .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","authorization,content-type,idempotency-key").method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(cors.statusCode()).isEqualTo(200); assertThat(cors.headers().firstValue("Access-Control-Allow-Headers").orElse("")).containsIgnoringCase("idempotency-key");
    }
    @Test @Order(10) void changedPermissionsCannotReplayFormerHqResponse() throws Exception {
        inviteAndActivate("former-hq@repair.example","ENGINEER",List.of());
        var initial=login("former-hq@repair.example");
        String id=initial.body.path("user").path("id").asText();
        assertThat(write("PUT","/api/v1/repair/members/"+id+"/access",Map.of("expectedVersion",0,"role","HQ_ADMIN","storeIds",List.of(),"reason","合成权限回放验证"),hq).status).isEqualTo(200);
        String formerToken=login("former-hq@repair.example").body.path("token").path("accessToken").asText();
        String key=UUID.randomUUID().toString();
        var profile=Map.of("expectedVersion",1,"displayName","临时总部","contactPhone","");
        assertThat(request("PATCH","/api/v1/repair/me/profile",profile,formerToken,key).status).isEqualTo(200);
        assertThat(write("PUT","/api/v1/repair/members/"+id+"/access",Map.of("expectedVersion",2,"role","ENGINEER","storeIds",List.of(),"reason","撤回临时权限"),hq).status).isEqualTo(200);
        assertThat(request("GET","/api/v1/repair/session",null,formerToken,null).body.path("code").asText()).isEqualTo("SESSION_REVOKED");
        String current=login("former-hq@repair.example").body.path("token").path("accessToken").asText();
        var replay=request("PATCH","/api/v1/repair/me/profile",profile,current,key);
        assertThat(replay.status).isEqualTo(409);
        assertThat(replay.body.path("code").asText()).isEqualTo("IDEMPOTENCY_CONTEXT_CHANGED");
        assertThat(replay.body.has("allowedStores")).isFalse();
        assertThat(request("GET","/api/v1/repair/members",null,current,null).status).isEqualTo(403);
    }
    @Test @Order(98) @EnabledIfSystemProperty(named="repair.browser",matches="true") void realBrowserFlow() throws Exception {
        // Force the new profile gate to be exercised by the real login, without changing its token generation.
        db.update("UPDATE repair_members SET display_name='' WHERE user_id=1");
        RepairBrowserSupport.run("repair-browser-test.cjs",Path.of("target/repair-browser.log"),base);
    }
    @Test @Order(99) void concurrentHqDisableAlwaysLeavesOneActiveAdmin() throws Exception {
        String storeId=db.queryForObject("SELECT CAST(user_id AS CHAR) FROM repair_members WHERE role='STORE_MANAGER' LIMIT 1",String.class);
        assertThat(write("PUT","/api/v1/repair/members/"+storeId+"/access",Map.of("expectedVersion",0,"role","HQ_ADMIN","storeIds",List.of(),"reason","合成总部授权"),hq).status).isEqualTo(200);
        var service=app.getBean(AccessService.class);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        var gate=new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(long id:List.of(1L,Long.parseLong(storeId))) futures.add(pool.submit(()->{
                gate.await();
                int version=db.queryForObject("SELECT version FROM repair_members WHERE user_id=?",Integer.class,id);
                try { service.changeStatus(id,id,new StatusRequest(version,"并发停用验证"),false); return true; }
                catch(RepairException ex) { assertThat(ex.getCode()).isEqualTo("LAST_HQ_ADMIN"); return false; }
            }));
            gate.countDown();
            long successes=0; for(var future:futures) if(future.get(15,TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM repair_members WHERE role='HQ_ADMIN' AND status='ACTIVE'",Long.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
}
