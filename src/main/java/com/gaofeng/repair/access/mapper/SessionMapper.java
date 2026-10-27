package com.gaofeng.repair.access.mapper;
import com.gaofeng.repair.access.domain.LoginSession;
import org.apache.ibatis.annotations.Param;
public interface SessionMapper {
    Long lockUser(long id);
    LoginSession session(String id);
    LoginSession lockSession(String id);
    void insertSession(LoginSession session);
    void revoke(@Param("id") String id,@Param("userId") long userId);
    void revokeAll(long userId);
    void bumpAuthVersion(long userId);
    int purgeExpired();
}
