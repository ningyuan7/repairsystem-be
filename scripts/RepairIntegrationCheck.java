import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaofeng.repair.bootstrap.RepairApplication;
import com.gaofeng.repair.ocr.service.OrderOcrClient;
import com.gaofeng.repair.ocr.service.OrderFieldExtractor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Explicit local provider verification; never edits business data or prints credentials. */
class RepairIntegrationCheck {
    public static void main(String[] args) throws Exception {
        Set<String> flags=Set.of(args);
        if(flags.stream().anyMatch(flag->!Set.of("--check-ocr").contains(flag)))throw new IllegalArgumentException("Unknown option");
        Map<String,Object> report=new LinkedHashMap<>(); report.put("checkedAt",Instant.now().toString());
        var application=new SpringApplication(RepairApplication.class);
        application.setDefaultProperties(Map.of("spring.config.name","repair"));
        boolean failed=false;
        try(ConfigurableApplicationContext context=application.run("--server.port=0","--server.address=127.0.0.1","--repair.workers.enabled=false","--repair.bootstrap.enabled=false","--spring.main.banner-mode=off","--logging.level.root=ERROR")) {
            try {
                var sender=context.getBean(JavaMailSenderImpl.class);
                if(sender.getUsername()==null||sender.getUsername().isBlank()||sender.getPassword()==null||sender.getPassword().isBlank()) {
                    report.put("smtp","NOT_CONFIGURED");failed=true;
                } else {
                    sender.testConnection(); report.put("smtp","AUTHENTICATED");
                    report.put("mailPolicy","REGISTRATION_AND_PASSWORD_RESET_ONLY");
                }
            } catch(Exception exception) {
                report.put("smtp","FAILED");report.put("smtpErrorType",exception.getClass().getSimpleName());failed=true;
            }
            var client=context.getBean(OrderOcrClient.class);
            if(!client.configured())report.put("ocr","NOT_CONFIGURED");
            else if(!flags.contains("--check-ocr"))report.put("ocr","CONFIGURED_NOT_CALLED");
            else try {
                BufferedImage image=new BufferedImage(1000,850,BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics=image.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);graphics.fillRect(0,0,1000,850);graphics.setColor(Color.BLACK);
                    graphics.setFont(new Font("SansSerif",Font.PLAIN,40));
                    String[] lines={"订单编号：LOCAL-OCR-CHECK","商品：温控器","品牌：美的","型号：LOCAL-2026","数量：1","单价：12.50","合计：12.50"};
                    for(int index=0;index<lines.length;index++)graphics.drawString(lines[index],50,90+index*90);
                } finally {graphics.dispose();}
                var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);
                var recognized=client.recognize(output.toByteArray());
                report.put("ocr","SUCCEEDED");report.put("ocrProvider",client.provider());report.put("ocrLineCount",recognized.lines().size()); var extraction=new OrderFieldExtractor().extract(recognized);report.put("ocrCandidateCount",extraction.candidates().items().size());report.put("ocrHasModel",extraction.candidates().items().stream().anyMatch(item->item.model()!=null));
            } catch(OrderOcrClient.OcrFailure exception) {
                report.put("ocr","FAILED");report.put("ocrErrorCode",exception.code());failed=true;
            }
        }
        Path output=Path.of("target/integration-check.json");
        var mapper=new ObjectMapper();mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        System.out.println(mapper.writeValueAsString(report));
        if(failed)System.exit(1);
    }
}
