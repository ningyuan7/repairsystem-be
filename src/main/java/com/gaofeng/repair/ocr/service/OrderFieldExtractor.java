package com.gaofeng.repair.ocr.service;
import com.gaofeng.repair.ocr.api.dto.OcrDtos.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

/** Conservative label-based extraction. Unlabelled/ambiguous text stays available as evidence. */
@Component
public class OrderFieldExtractor {
    private static final String STOP="(?=\\s+(?:品牌|型号|数量|单价|商品|品名|合计|总额|运费|优惠|折扣)\\s*[:：]?|$)";
    public Extraction extract(Recognition source) {
        List<Line> lines=source.lines().stream().limit(2000).toList(); List<Item> items=new ArrayList<>(); List<String> warnings=new ArrayList<>(List.of("HUMAN_CONFIRMATION_REQUIRED"));
        Field<String> number=label(lines,"(?:订单编号|订单号)","[A-Za-z0-9_-]{1,128}"),supplier=label(lines,"(?:供应商|商家|店铺)",".{1,128}?"+STOP);
        Field<String> time=label(lines,"(?:下单时间|创建时间)","[0-9]{4}[-/][0-9]{2}[-/][0-9]{2}[ T][0-9]{2}:[0-9]{2}(?::[0-9]{2})?");
        if(time!=null) try { String date=time.value().replace('/','-').replace(' ','T'); time=new Field<>(LocalDateTime.parse(date,DateTimeFormatter.ISO_LOCAL_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toString(),time.evidence(),time.lineIndexes(),time.confidence()); } catch(DateTimeException ex) { warnings.add("ORDERED_AT_AMBIGUOUS"); time=null; }
        int start=-1;
        for(int i=0;i<=lines.size();i++) {
            boolean next=i<lines.size() && Pattern.compile("^(?:商品(?:名称)?|品名)\\s*[:：]\\s*\\S").matcher(lines.get(i).text()).find();
            if(start>=0 && (next || i==lines.size())) {
                List<Line> block=lines.subList(start,i); Field<String> name=label(block,"(?:商品(?:名称)?|品名)",".{1,128}?"+STOP);
                Field<String> qty=label(block,"数量","[0-9]{1,3}(?![0-9.])"),price=money(block,"单价"),brand=label(block,"品牌",".{1,128}?"+STOP),model=label(block,"型号",".{1,128}?"+STOP);
                Field<Integer> quantity=qty==null?null:new Field<>(Integer.valueOf(qty.value()),qty.evidence(),offset(qty.lineIndexes(),start),qty.confidence());
                if(quantity!=null && quantity.value()<1) quantity=null;
                items.add(new Item(shift(name,start),shift(brand,start),shift(model,start),quantity,shift(price,start)));
                if(brand==null) warnings.add("ITEM_"+(items.size()-1)+"_BRAND_MISSING"); if(model==null) warnings.add("ITEM_"+(items.size()-1)+"_MODEL_MISSING");
                if(quantity==null || price==null) warnings.add("ITEM_"+(items.size()-1)+"_QUANTITY_OR_PRICE_MISSING");
                if(items.size()==100) { warnings.add("ITEM_LIMIT_REACHED"); break; }
            }
            if(next) start=i;
        }
        if(items.isEmpty()) warnings.add("NO_LABELLED_ITEM_FOUND");
        Field<String> freight=money(lines,"运费"),discount=money(lines,"(?:优惠|折扣)"),adjustment=null;
        if(freight!=null || discount!=null) {
            BigDecimal value=(freight==null?BigDecimal.ZERO:new BigDecimal(freight.value())).subtract(discount==null?BigDecimal.ZERO:new BigDecimal(discount.value()));
            List<Integer> evidence=new ArrayList<>(); if(freight!=null) evidence.addAll(freight.lineIndexes()); if(discount!=null) evidence.addAll(discount.lineIndexes());
            adjustment=new Field<>(value.setScale(2).toPlainString(),String.join(" / ",evidence.stream().map(index->lines.get(index).text()).toList()),evidence,null);
        }
        Field<String> total=money(lines,"(?:实付款|支付总额|订单总额|合计)");
        if(total!=null && !items.isEmpty() && items.stream().allMatch(item->item.quantity()!=null && item.unitPrice()!=null)) {
            BigDecimal sum=items.stream().map(item->new BigDecimal(item.unitPrice().value()).multiply(BigDecimal.valueOf(item.quantity().value()))).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(adjustment!=null) sum=sum.add(new BigDecimal(adjustment.value())); if(sum.compareTo(new BigDecimal(total.value()))!=0) warnings.add("SOURCE_TOTAL_MISMATCH");
        }
        return new Extraction(new Candidates(number,supplier,time,List.copyOf(items),adjustment,total),lines,List.copyOf(warnings),source.confidenceUnit());
    }
    private static Field<String> money(List<Line> lines,String name) { Field<String> raw=label(lines,name,"[¥￥]?\\s*[0-9]{1,10}(?:\\.[0-9]{1,2})?(?![0-9.])"); if(raw==null)return null;return new Field<>(new BigDecimal(raw.value().replaceAll("[¥￥\\s]","")).setScale(2).toPlainString(),raw.evidence(),raw.lineIndexes(),raw.confidence()); }
    private static Field<String> label(List<Line> lines,String name,String value) {
        Pattern pattern=Pattern.compile("(?:^|\\s)"+name+"\\s*[:：]?\\s*("+value+")"); Field<String> result=null;
        for(int i=0;i<lines.size();i++) { Line line=lines.get(i); Matcher match=pattern.matcher(line.text()); if(match.find()) { String text=match.group(1).strip(); if(text.isEmpty()) continue; if(result!=null && !result.value().equals(text)) return null; result=new Field<>(text,line.text(),List.of(i),line.confidence()); } }
        return result;
    }
    private static List<Integer> offset(List<Integer> indexes,int offset) { return indexes.stream().map(index->index+offset).toList(); }
    private static <T> Field<T> shift(Field<T> field,int offset) { return field==null?null:new Field<>(field.value(),field.evidence(),offset(field.lineIndexes(),offset),field.confidence()); }
}
