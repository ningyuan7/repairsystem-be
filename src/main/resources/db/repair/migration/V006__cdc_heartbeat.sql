-- Operational heartbeat, never used to authorize or transition business state.
CREATE TABLE repair_cdc_heartbeat (
 id TINYINT NOT NULL PRIMARY KEY,
 sent_at BIGINT NOT NULL
) ENGINE=InnoDB;
