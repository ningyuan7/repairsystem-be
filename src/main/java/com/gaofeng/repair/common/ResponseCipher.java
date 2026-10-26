package com.gaofeng.repair.common;

import com.gaofeng.auth.config.AuthProperties;
import com.gaofeng.auth.config.PemUtils;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Idempotent invitation replay must not persist a plaintext activation secret. */
@Component
public class ResponseCipher {
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();
    public ResponseCipher(AuthProperties properties) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update("repair-idempotency-v1".getBytes(StandardCharsets.UTF_8));
            key=new SecretKeySpec(digest.digest(PemUtils.readPrivateKey(properties.getJwt().getPrivateKey()).getEncoded()),"AES");
        } catch(Exception ex) { throw new IllegalStateException("Cannot initialize response protection",ex); }
    }
    public String encrypt(String plain) {
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            byte[] encrypted=cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] result=Arrays.copyOf(nonce,nonce.length+encrypted.length); System.arraycopy(encrypted,0,result,nonce.length,encrypted.length);
            return Base64.getEncoder().encodeToString(result);
        } catch(Exception ex) { throw new IllegalStateException("Cannot protect response",ex); }
    }
    public String decrypt(String stored) {
        try {
            byte[] bytes=Base64.getDecoder().decode(stored);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
            return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
        } catch(Exception ex) { throw new IllegalStateException("Cannot restore response",ex); }
    }
}
