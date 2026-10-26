package com.gaofeng.repair.ticket.domain;
public enum TicketStatus {
    SUBMITTED, WAITING_PROCUREMENT, READY_FOR_DISPATCH, ASSIGNED, ACCEPTED, IN_PROGRESS,
    WAITING_ACCEPTANCE, COMPLETED, CANCELLED, REJECTED;
    public boolean terminal() { return this==COMPLETED || this==CANCELLED || this==REJECTED; }
}
