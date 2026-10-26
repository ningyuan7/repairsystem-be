package com.gaofeng.repair.access.mapper;

import com.gaofeng.repair.access.domain.*;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

public interface AccessMapper {
    Member member(long id);
    Member lockMember(long id);
    List<Member> members(@Param("offset") int offset, @Param("size") int size);
    long countMembers();
    void insertMember(Member member);
    int updateMember(Member member);
    void updateProfile(Member member);
    List<Store> stores(@Param("userId") long userId, @Param("all") boolean all);
    Store store(long id);
    Store lockStore(long id);
    void insertStore(Store store);
    int updateStore(Store store);
    void addStore(@Param("userId") long userId, @Param("storeId") long storeId);
    void removeStores(long userId);
    String governanceLock();
    long activeHqCount();
    void insertInvitation(Invitation invitation);
    Invitation invitation(String tokenHash);
    Invitation lockInvitation(String tokenHash);
    void consumeInvitation(String id);
    void audit(@Param("actor") long actor, @Param("action") String action, @Param("type") String type,
               @Param("id") String id, @Param("requestId") String requestId, @Param("summary") String summary);
    IdempotencyRecord idempotency(@Param("userId") long userId, @Param("operation") String operation, @Param("key") String key);
    void reserveIdempotency(@Param("userId") long userId, @Param("operation") String operation, @Param("key") String key,
                            @Param("hash") String hash, @Param("authVersion") int authVersion, @Param("expires") Instant expires);
    void completeIdempotency(@Param("userId") long userId, @Param("operation") String operation, @Param("key") String key,
                             @Param("status") int status, @Param("json") String json);
    void deleteExpiredIdempotency(@Param("userId") long userId, @Param("operation") String operation, @Param("key") String key);
}
