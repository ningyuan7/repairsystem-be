package com.gaofeng.repair.ocr.mapper;
import com.gaofeng.repair.ocr.domain.OcrJob;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
public interface OcrMapper {
    void insert(OcrJob job);
    OcrJob get(String id);
    OcrJob lock(String id);
    List<OcrJob> forOrder(long id);
    OcrJob next();
    int claim(@Param("id") String id,@Param("token") String token);
    int exhausted();
    int finish(@Param("id") String id,@Param("token") String token,@Param("status") String status,@Param("result") String result,
               @Param("error") String error,@Param("requestId") String requestId,@Param("nextRun") Instant nextRun);
}
