package com.gaofeng.repair.report.service;
import com.gaofeng.repair.report.domain.ExportRow;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class RepairCsv {
    private RepairCsv(){}
    public static void write(OutputStream stream,List<ExportRow> rows) throws IOException {
        Writer writer=new BufferedWriter(new OutputStreamWriter(stream,StandardCharsets.UTF_8));writer.write('\uFEFF');
        line(writer,List.of("工单号","标题","门店","状态","故障设施","原品牌","原型号","当前工程师","提交时间（UTC）","完成时间（UTC）","采购记录金额（元）","已确认总费用（元，空白为待核算）"));
        for(ExportRow row:rows) line(writer,Arrays.asList(row.getTicketNo(),row.getTitle(),row.getStoreName(),row.getStatus(),row.getFacilityName(),row.getOriginalBrand(),row.getOriginalModel(),row.getAssigneeName(),row.getSubmittedAt()==null?null:row.getSubmittedAt().toString(),row.getCompletedAt()==null?null:row.getCompletedAt().toString(),ReportService.money(row.getPurchaseAmount()),row.getTotalAmount()==null?null:ReportService.money(row.getTotalAmount())));
        writer.flush();
    }
    private static void line(Writer writer,List<String> cells)throws IOException{for(int i=0;i<cells.size();i++){if(i>0)writer.write(',');writer.write(cell(cells.get(i)));}writer.write("\r\n");}
    public static String cell(String input){String value=input==null?"":input;String first=value.stripLeading();if(!first.isEmpty() && "=+-@".indexOf(first.charAt(0))>=0 || (!value.isEmpty() && "\t\r\n".indexOf(value.charAt(0))>=0))value="'"+value;return "\""+value.replace("\"","\"\"")+"\"";}
}
