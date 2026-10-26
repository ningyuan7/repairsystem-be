package com.gaofeng.repair.common;

import com.gaofeng.repair.access.mapper.AccessMapper;
import com.gaofeng.repair.access.domain.IdempotencyRecord;
import com.gaofeng.repair.access.service.RepairAccess;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyService {
    private final AccessMapper mapper;
    private final JsonSupport json;
    private final ResponseCipher cipher;
    private final RepairAccess access;
    private final TransactionTemplate transaction;
    public IdempotencyService(AccessMapper mapper,JsonSupport json,ResponseCipher cipher,RepairAccess access,PlatformTransactionManager manager) {
        this.mapper=mapper; this.json=json; this.cipher=cipher; this.access=access; this.transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public ResponseEntity<JsonNode> execute(long userId,String operation,String key,Object body,int status,Supplier<?> command) {
        try { if(!UUID.fromString(key).toString().equalsIgnoreCase(key)) throw new IllegalArgumentException(); }
        catch(RuntimeException ex) { throw RepairException.invalid("Idempotency-Key 必须是 UUID"); }
        String hash=JsonSupport.sha256(json.write(body));
        int authVersion=access.active(userId).getAuthVersion();
        // A duplicate reservation is handled outside the failed transaction, never by continuing a poisoned TX.
        try {
            return transaction.execute(tx -> {
                IdempotencyRecord cached=mapper.idempotency(userId,operation,key);
                if(cached!=null) return replay(cached,hash,authVersion);
                mapper.deleteExpiredIdempotency(userId,operation,key);
                mapper.reserveIdempotency(userId,operation,key,hash,authVersion,Instant.now().plus(Duration.ofHours(24)));
                Object result=command.get();
                String response=json.write(result);
                mapper.completeIdempotency(userId,operation,key,status,json.write(cipher.encrypt(response)));
                return ResponseEntity.status(status).body(json.read(response,JsonNode.class));
            });
        } catch(DuplicateKeyException ex) {
            var cached=transaction.execute(tx->mapper.idempotency(userId,operation,key));
            if(cached!=null) return replay(cached,hash,authVersion);
            throw ex;
        }
    }
    private ResponseEntity<JsonNode> replay(IdempotencyRecord cached,String hash,int authVersion) {
        if(cached.getAuthVersion()!=authVersion) throw new RepairException(409,"IDEMPOTENCY_CONTEXT_CHANGED","权限已变更，无法回放原请求，请重新操作");
        if(!hash.equals(cached.getRequestHash())) throw new RepairException(409,"IDEMPOTENCY_CONFLICT","同一请求标识不能用于不同内容");
        return ResponseEntity.status(cached.getHttpStatus()).body(json.read(cipher.decrypt(json.read(cached.getResponseJson(),String.class)),JsonNode.class));
    }
}
