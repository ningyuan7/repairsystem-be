package com.gaofeng.repair.bootstrap;
import com.gaofeng.repair.access.domain.*;
import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.repair.access.service.*;
import com.gaofeng.user.service.UserService;
import com.gaofeng.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;
import java.util.Map;
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="repair.bootstrap.enabled",havingValue="true")
public class RepairBootstrap implements ApplicationRunner {
    private final AccessMapper mapper;
    private final UserService users;
    private final AccessService audit;
    private final PasswordEncoder passwords;
    private final Environment environment;
    @Override @Transactional public void run(ApplicationArguments args) {
        mapper.governanceLock();
        if(mapper.activeHqCount()>0) return;
        String email=environment.getRequiredProperty("repair.bootstrap.email").strip().toLowerCase(Locale.ROOT);
        String password=environment.getRequiredProperty("repair.bootstrap.password");
        String name=environment.getRequiredProperty("repair.bootstrap.name").strip();
        if(!com.gaofeng.auth.util.IdentifierValidator.isValidEmail(email) || email.length()>128 || name.isBlank() || name.length()>64)
            throw new IllegalStateException("Bootstrap requires a valid email and display name");
        InvitationService.validatePassword(password);
        if(users.findByEmail(email).isPresent()) throw new IllegalStateException("Bootstrap will not overwrite an existing account");
        User user=users.createUser(User.builder().email(email).nickname(name).avatar("").tagsJson("[]").passwordHash(passwords.encode(password)).build());
        Member member=new Member(); member.setUserId(user.getId()); member.setRole(Role.HQ_ADMIN); member.setStatus("ACTIVE"); member.setDisplayName(name); member.setContactPhone("");
        mapper.insertMember(member);
        audit.audit(user.getId(),"HQ_BOOTSTRAP","MEMBER",user.getId().toString(),Map.of("method","local-explicit-bootstrap"));
    }
}
