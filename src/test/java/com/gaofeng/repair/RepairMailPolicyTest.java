package com.gaofeng.repair;
import com.gaofeng.auth.verification.VerificationScene;
import com.gaofeng.repair.bootstrap.RepairCodeSender;
import com.gaofeng.repair.common.RepairException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RepairMailPolicyTest {
    JavaMailSender mail=mock(JavaMailSender.class);
    RepairCodeSender sender(){
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("mail",mail);
        return new RepairCodeSender(beans.getBeanProvider(JavaMailSender.class),new MockEnvironment().withProperty("spring.mail.username","sender@repair.example"));
    }
    @ParameterizedTest @EnumSource(value=VerificationScene.class,names={"REGISTER","RESET_PASSWORD"})
    void onlyRegistrationAndPasswordResetCanSendVerificationMail(VerificationScene scene){
        sender().sendCode(scene,"member@repair.example","123456",5);
        var captured=org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail).send(captured.capture());
        assertThat(captured.getValue().getTo()).containsExactly("member@repair.example");
        assertThat(captured.getValue().getText()).contains("123456");verifyNoMoreInteractions(mail);
    }
    @Test void loginCodeCannotSendMail(){
        assertThatThrownBy(()->sender().sendCode(VerificationScene.LOGIN,"member@repair.example","123456",5))
            .isInstanceOf(RepairException.class).extracting("status","code").containsExactly(403,"VERIFICATION_SCENE_DISABLED");
        verifyNoInteractions(mail);
    }
}

