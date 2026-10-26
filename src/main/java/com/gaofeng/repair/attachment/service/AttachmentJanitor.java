package com.gaofeng.repair.attachment.service;
import com.gaofeng.repair.attachment.mapper.AttachmentMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
@Component @RequiredArgsConstructor
public class AttachmentJanitor {
    private final AttachmentMapper mapper;private final AttachmentService storage;private final PlatformTransactionManager transactions;
    @Value("${repair.workers.enabled:true}") private boolean enabled;
    private DirectoryStream<Path> scan;private Iterator<Path> iterator;
    @Scheduled(fixedDelayString="${repair.storage.cleanup-ms:3600000}",initialDelayString="${repair.storage.cleanup-ms:3600000}") public void poll(){if(enabled)cleanup();}
    public synchronized int cleanup() {
        var expired=new TransactionTemplate(transactions).execute(tx->{var rows=mapper.expired();for(var row:rows)mapper.expire(row.getId());return rows;});int deleted=0;
        for(var image:expired) if(storage.deletePrivateObject(image)){mapper.deleted(image.getId());deleted++;}
        // Continue a bounded directory scan across runs; never recurse or follow links.
        try {
            if(scan==null){scan=Files.newDirectoryStream(storage.privateRoot());iterator=scan.iterator();}
            int visited=0;while(iterator.hasNext() && visited++<1000) {
                Path file=iterator.next();String name=file.getFileName().toString();
                if(!file.getParent().equals(storage.privateRoot()) || !name.matches("[a-f0-9-]{36}\\.(png|jpg)") || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;
                if(Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(Instant.now().minus(Duration.ofHours(48))) || mapper.objectReferenced(name))continue;
                Files.deleteIfExists(file);deleted++;
            }
            if(!iterator.hasNext())close();
        }catch(java.io.IOException ex){close();}
        return deleted;
    }
    @PreDestroy public synchronized void close(){if(scan!=null)try{scan.close();}catch(java.io.IOException ignored){}scan=null;iterator=null;}
}
