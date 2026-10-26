import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaofeng.repair.bootstrap.RepairApplication;
import com.gaofeng.repair.cache.EngineerReadCache;
import org.springframework.boot.SpringApplication;
import java.nio.file.*;
import java.util.*;
class RepairReadCacheCheck {
 public static void main(String[] args)throws Exception{
  var application=new SpringApplication(RepairApplication.class);application.setDefaultProperties(Map.of("spring.config.name","repair"));
  try(var context=application.run("--server.port=0","--server.address=127.0.0.1","--repair.canal.enabled=false","--repair.workers.enabled=false","--repair.bootstrap.enabled=false","--spring.main.banner-mode=off","--logging.level.root=ERROR")){
   var cache=context.getBean(EngineerReadCache.class);var status=cache.status();var report=Map.of("checkedAt",java.time.Instant.now().toString(),"cache",status);
   String encoded=context.getBean(ObjectMapper.class).writeValueAsString(report);Files.writeString(Path.of("target/read-cache-status.json"),encoded);System.out.println(encoded);
   if(!status.enabled()||!status.cdcFresh())System.exit(2);
  }
 }
}
