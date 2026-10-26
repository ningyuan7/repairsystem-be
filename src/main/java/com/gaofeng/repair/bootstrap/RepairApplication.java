package com.gaofeng.repair.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.Map;

/** Standalone store facility repair application. */
@SpringBootApplication(scanBasePackages = "com.gaofeng.repair")
@MapperScan({"com.gaofeng.repair.cache.mapper", "com.gaofeng.repair.access.mapper", "com.gaofeng.repair.asset.mapper", "com.gaofeng.repair.ticket.mapper",
        "com.gaofeng.repair.attachment.mapper", "com.gaofeng.repair.procurement.mapper", "com.gaofeng.repair.ocr.mapper", "com.gaofeng.repair.notification.mapper", "com.gaofeng.repair.report.mapper", "com.gaofeng.user.mapper", "com.gaofeng.auth.audit"})
public class RepairApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(RepairApplication.class);
        app.setDefaultProperties(Map.of("spring.config.name", "repair"));
        app.run(args);
    }
}
