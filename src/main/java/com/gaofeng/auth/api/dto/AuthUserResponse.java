package com.gaofeng.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import org.springframework.util.StringUtils;

/**
 * 认证用户响应。
 * <p>
 * 面向客户端展示的基础用户信息，供“我是谁”与首页显示使用。
 */
public record AuthUserResponse(
        Long id,
        String nickname,
        String avatar,
        String phone,
        String email,
        String zhId,
        LocalDate birthday,
        String school,
        String bio,
        String gender,
        String tagJson
) {
    /** 由已保存的资料计算，登录、注册及恢复会话使用同一判断。 */
    @JsonProperty("profileComplete")
    public boolean profileComplete() {
        return StringUtils.hasText(nickname) && StringUtils.hasText(avatar)
                && birthday != null && !birthday.isAfter(LocalDate.now());
    }
}
