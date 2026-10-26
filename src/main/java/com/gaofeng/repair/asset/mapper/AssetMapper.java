package com.gaofeng.repair.asset.mapper;
import com.gaofeng.repair.asset.domain.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface AssetMapper {
    List<Category> categories(boolean all);
    Category category(long id);
    Category lockCategory(long id);
    void insertCategory(Category category);
    int updateCategory(Category category);
    Asset asset(long id);
    Asset lockAsset(long id);
    List<Asset> list(@Param("actor") long actor,@Param("hq") boolean hq,@Param("storeId") Long storeId,
                     @Param("condition") String condition,@Param("lifecycle") String lifecycle,@Param("keyword") String keyword,
                     @Param("offset") int offset,@Param("size") int size);
    long count(@Param("actor") long actor,@Param("hq") boolean hq,@Param("storeId") Long storeId,
               @Param("condition") String condition,@Param("lifecycle") String lifecycle,@Param("keyword") String keyword);
    void insertAsset(Asset asset);
    int updateAsset(Asset asset);
    Long activeTicket(long assetId);
    void updateHealth(@Param("id") long id,@Param("health") String health);
}
