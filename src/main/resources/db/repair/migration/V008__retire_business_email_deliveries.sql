-- Business notifications are in-app only. Preserve historical delivery records, revoke pending/failed retries.
UPDATE repair_delivery_jobs
SET status='CANCELLED',last_error_code='NOTIFICATION_IN_APP_ONLY',lease_token=NULL,lease_until=NULL
WHERE channel='EMAIL' AND status IN ('QUEUED','RUNNING','FAILED');
