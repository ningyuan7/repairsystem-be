-- Preserve existing order: submitted_at DESC, id DESC. No business state changes.
CREATE INDEX idx_repair_engineer_recent ON repair_tickets(assignee_id,submitted_at DESC,id DESC);
CREATE INDEX idx_repair_engineer_state_recent ON repair_tickets(assignee_id,status,submitted_at DESC,id DESC);
CREATE INDEX idx_repair_active_engineers ON repair_members(role,status,user_id);
