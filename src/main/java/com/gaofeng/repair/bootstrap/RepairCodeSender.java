package com.gaofeng.repair.bootstrap;
import com.gaofeng.auth.verification.*;
import com.gaofeng.auth.util.IdentifierValidator;
import com.gaofeng.repair.common.RepairException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
@Component
@RequiredArgsConstructor
public class RepairCodeSender implements CodeSender {
    private final ObjectProvider<JavaMailSender> sender;
    private final Environment environment;
    @Override public void checkAvailable(String identifier) {
        if(!IdentifierValidator.isValidEmail(identifier)) throw RepairException.invalid("当前仅支持邮箱验证码");
        if(sender.getIfAvailable()==null || !IdentifierValidator.isValidEmail(environment.getProperty("spring.mail.username","")))
            throw new RepairException(503,"MAIL_NOT_CONFIGURED","邮件服务尚未配置，请使用密码登录或联系总部管理员");
    }
    @Override public void sendCode(VerificationScene scene,String identifier,String code,int minutes) {
        if(scene!=VerificationScene.REGISTER && scene!=VerificationScene.RESET_PASSWORD)
            throw new RepairException(403,"VERIFICATION_SCENE_DISABLED","邮件验证码仅用于注册验证或找回密码");
        checkAvailable(identifier);
        var message=new SimpleMailMessage(); message.setFrom(environment.getRequiredProperty("spring.mail.username")); message.setTo(identifier);
        message.setSubject("门店设施报修 · 验证码"); message.setText("验证码："+code+"\n"+minutes+" 分钟内有效，请勿向他人提供。\n非本人操作请忽略。");
        try { sender.getObject().send(message); }
        catch(org.springframework.mail.MailException ex) { throw new RepairException(503,"MAIL_DELIVERY_FAILED","邮件发送失败，请稍后重试"); }
    }
}
