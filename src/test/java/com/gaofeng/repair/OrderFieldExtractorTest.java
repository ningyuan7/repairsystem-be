package com.gaofeng.repair;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaofeng.repair.ocr.api.dto.OcrDtos.*;
import com.gaofeng.repair.ocr.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class OrderFieldExtractorTest {
    @Test void tc3SignatureMatchesIndependentReferenceAndUsesUtcDate() {
        assertThat(TencentOrderOcrClient.authorization("AKIDEXAMPLE","SECRETEXAMPLE","cvm","cvm.tencentcloudapi.com","DescribeInstances",1551113065L,"{\"Limit\":1}"))
            .isEqualTo("TC3-HMAC-SHA256 Credential=AKIDEXAMPLE/2019-02-25/cvm/tc3_request, SignedHeaders=content-type;host;x-tc-action, Signature=bfccaa6f2c613cb23a89cd7af659c8537218a093b351fb75cc433f3143579aee");
    }
    static Recognition sample(String... text) { return new Recognition(Arrays.stream(text).map(line->new Line(line,null,List.of())).toList(),"synthetic-request", "unknown"); }
    @Test void multipleProductsMissingFactsAndExactDecimalTotals() {
        var extracted=new OrderFieldExtractor().extract(sample("订单号: SYNTH-2026","商家: 合成供应商","下单时间: 2026-10-25 09:30","商品: 冷柜温控器 品牌: 合成品牌 型号: TC-26 数量: 2 单价: 39.90","商品: 安装支架 数量: 1 单价: 10.00","运费: 5.00","优惠: 4.80","实付款: 90.00"));
        assertThat(extracted.candidates().items()).hasSize(2); assertThat(extracted.candidates().items().get(0).name().value()).isEqualTo("冷柜温控器");
        assertThat(extracted.candidates().items().get(1).brand()).isNull(); assertThat(extracted.candidates().items().get(1).model()).isNull();
        assertThat(extracted.candidates().items().get(1).quantity().value()).isEqualTo(1); assertThat(extracted.candidates().adjustmentAmount().value()).isEqualTo("0.20");
        assertThat(extracted.candidates().orderedAt().value()).isEqualTo("2026-10-25T01:30:00Z"); assertThat(extracted.warnings()).doesNotContain("SOURCE_TOTAL_MISMATCH");
        assertThat(extracted.candidates().items().get(0).name().confidence()).isNull(); assertThat(extracted.candidates().items().get(0).name().lineIndexes()).containsExactly(3);
    }
    @Test void ambiguitiesAndNoInventedQuantityOrModel() {
        var result=new OrderFieldExtractor().extract(sample("订单号: ONE","订单号: TWO","商品: 温控器","数量: 0","单价: 168.00","下单时间: 2026-02-31 09:30","合计: 168.00"));
        assertThat(result.candidates().orderNumber()).isNull(); assertThat(result.candidates().orderedAt()).isNull(); assertThat(result.candidates().items().get(0).quantity()).isNull(); assertThat(result.candidates().items().get(0).model()).isNull();
        assertThat(result.warnings()).contains("ORDERED_AT_AMBIGUOUS","HUMAN_CONFIRMATION_REQUIRED");
    }
    @Test void noProductFromTotalsAndMismatchIsWarning() {
        assertThat(new OrderFieldExtractor().extract(sample("合计: 399.00","广告文字")).candidates().items()).isEmpty();
        assertThat(new OrderFieldExtractor().extract(sample("商品: 冷柜 数量: 1 单价: 399.00","合计: 398.00")).warnings()).contains("SOURCE_TOTAL_MISMATCH");
    }
    @Test void parsesActualVendorContractAndNormalizesOnlyRealConfidence() throws Exception {
        var result=TencentOrderOcrClient.parse(new ObjectMapper().readTree("""
          {"Response":{"RequestId":"synthetic-id","TextDetections":[{"DetectedText":"商品: 合成设备","Confidence":95,"Polygon":[{"X":12,"Y":34}]},{"DetectedText":"型号未标注"}]}}
          """));
        assertThat(result.lines().get(0).confidence()).isEqualTo(0.95); assertThat(result.lines().get(0).polygon()).containsExactly(new Point(12,34)); assertThat(result.lines().get(1).confidence()).isNull();
    }
    @Test void classifiesVendorFailuresWithoutLeakingMessage() throws Exception {
        var root=new ObjectMapper().readTree("{\"Response\":{\"RequestId\":\"id\",\"Error\":{\"Code\":\"RequestLimitExceeded\",\"Message\":\"private order text\"}}}");
        assertThatThrownBy(()->TencentOrderOcrClient.parse(root)).isInstanceOfSatisfying(OrderOcrClient.OcrFailure.class,ex->{assertThat(ex.transientFailure()).isTrue();assertThat(ex.getMessage()).doesNotContain("private");});
        assertThat(new TencentOrderOcrClient(false,"synthetic","synthetic","ap-guangzhou",new ObjectMapper()).configured()).isFalse();
    }
}
