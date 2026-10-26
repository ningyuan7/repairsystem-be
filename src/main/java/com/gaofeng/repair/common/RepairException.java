package com.gaofeng.repair.common;
import lombok.Getter;
@Getter
public class RepairException extends RuntimeException {
    private final int status;
    private final String code;
    public RepairException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public static RepairException forbidden() { return new RepairException(403,"FORBIDDEN","无权执行此操作"); }
    public static RepairException missing() { return new RepairException(404,"NOT_FOUND","内容不存在"); }
    public static RepairException invalid(String message) { return new RepairException(400,"BAD_REQUEST",message); }
    public static void version(int expected,int actual) {
        if(expected!=actual) throw new RepairException(409,"VERSION_CONFLICT","内容已更新，请刷新后重试，当前输入已保留");
    }
}
