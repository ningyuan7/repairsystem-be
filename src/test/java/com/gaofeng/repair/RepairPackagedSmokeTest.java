package com.gaofeng.repair;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import static com.gaofeng.repair.RepairIdentityIntegrationTest.*;
import static org.assertj.core.api.Assertions.*;

/** Runs the real executable artifact against only the isolated test database, no provider calls. */
@EnabledIfSystemProperty(named="repair.packaged",matches="true")
class RepairPackagedSmokeTest {
    @BeforeAll static void fixture()throws Exception{RepairIdentityIntegrationTest.prepare();}
    @AfterAll static void cleanupFixture()throws Exception{RepairIdentityIntegrationTest.cleanup();}
    @Test void executableJarStartsWithOnlyRepairResourcesAndDependencies()throws Exception{
        Path artifact=Path.of("target/repair-system.jar").toAbsolutePath();assertThat(Files.isRegularFile(artifact)).isTrue();Path log=Path.of("target/repair-packaged-smoke.log");
        String binary=System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java";
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",binary).toString(),"-jar",artifact.toString());
        var environment=builder.environment();var configuration=app.getEnvironment();
        environment.put("REPAIR_DB_URL",configuration.getRequiredProperty("spring.datasource.url"));environment.put("REPAIR_DB_USERNAME",configuration.getRequiredProperty("spring.datasource.username"));environment.put("REPAIR_DB_PASSWORD",configuration.getProperty("spring.datasource.password",""));
        environment.put("REPAIR_REDIS_HOST",configuration.getRequiredProperty("spring.data.redis.host"));environment.put("REPAIR_REDIS_PORT",configuration.getRequiredProperty("spring.data.redis.port"));environment.put("REPAIR_REDIS_PASSWORD",configuration.getProperty("spring.data.redis.password",""));environment.put("REPAIR_REDIS_DATABASE","15");
        environment.put("REPAIR_JWT_PRIVATE_KEY",configuration.getRequiredProperty("auth.jwt.private-key"));environment.put("REPAIR_JWT_PUBLIC_KEY",configuration.getRequiredProperty("auth.jwt.public-key"));
        environment.put("REPAIR_PORT","0");environment.put("REPAIR_BOOTSTRAP_ENABLED","false");environment.put("REPAIR_WORKERS_ENABLED","false");environment.put("REPAIR_OCR_ENABLED","false");environment.put("REPAIR_MAIL_ENABLED","false");
        builder.command().add("--repair.storage.local-root="+configuration.getRequiredProperty("repair.storage.local-root"));builder.redirectErrorStream(true);builder.redirectOutput(log.toFile());Process process=builder.start();
        try{
            int port=0;Instant deadline=Instant.now().plusSeconds(45);Pattern started=Pattern.compile("Tomcat started on port (\\d+)");
            while(Instant.now().isBefore(deadline)&&process.isAlive()){
                var match=started.matcher(Files.exists(log)?Files.readString(log):"");if(match.find()){port=Integer.parseInt(match.group(1));break;}Thread.sleep(100);
            }
            assertThat(port).describedAs("Executable jar startup; see "+log).isPositive();
            var health=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health")).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());assertThat(health.statusCode()).isEqualTo(200);
            var session=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/repair/session")).header("Authorization","Bearer "+hq).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());assertThat(session.statusCode()).isEqualTo(200);assertThat(json.readTree(session.body()).path("role").asText()).isEqualTo("HQ_ADMIN");
            var legacy=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/campus/me")).header("Authorization","Bearer "+hq).build(),HttpResponse.BodyHandlers.ofString());assertThat(legacy.statusCode()).isEqualTo(404);
        }finally{process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
}
