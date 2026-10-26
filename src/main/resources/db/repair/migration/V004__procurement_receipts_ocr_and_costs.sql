CREATE TABLE repair_purchase_orders (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, ticket_id BIGINT UNSIGNED NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'DRAFT', order_number VARCHAR(128), supplier VARCHAR(128) NOT NULL DEFAULT '', ordered_at DATETIME(3),
 currency CHAR(3) NOT NULL DEFAULT 'CNY', adjustment_amount DECIMAL(14,2) NOT NULL DEFAULT 0, confirmed_total DECIMAL(14,2) NOT NULL DEFAULT 0,
 received_at DATETIME(3), version INT UNSIGNED NOT NULL DEFAULT 0,
 active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status <> 'CANCELLED' THEN 1 ELSE NULL END) STORED,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(ticket_id,active_slot), INDEX(status,ordered_at), CHECK(confirmed_total>=0), CHECK(currency='CNY'),
 CHECK(status IN ('DRAFT','ORDERED','PARTIALLY_RECEIVED','RECEIVED','CANCELLED')), FOREIGN KEY(ticket_id) REFERENCES repair_tickets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE repair_purchase_items (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, purchase_order_id BIGINT UNSIGNED NOT NULL, purpose VARCHAR(24) NOT NULL,
 name VARCHAR(128) NOT NULL, brand VARCHAR(128), model VARCHAR(128), quantity INT NOT NULL, unit_price DECIMAL(12,2) NOT NULL,
 received_quantity INT NOT NULL DEFAULT 0, spec_confirmed BOOLEAN NOT NULL DEFAULT FALSE, spec_confirmed_by BIGINT UNSIGNED, spec_confirmed_at DATETIME(3),
 source_ocr_job_id CHAR(36), source_candidate_index INT, CHECK(purpose IN ('PART','REPLACEMENT')),
 CHECK(quantity BETWEEN 1 AND 999), CHECK(unit_price>=0), CHECK(received_quantity>=0 AND received_quantity<=quantity),
 INDEX(purchase_order_id,id), FOREIGN KEY(purchase_order_id) REFERENCES repair_purchase_orders(id), FOREIGN KEY(spec_confirmed_by) REFERENCES repair_members(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE repair_material_receipts (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, purchase_order_id BIGINT UNSIGNED NOT NULL, receiver_id BIGINT UNSIGNED NOT NULL,
 received_at DATETIME(3) NOT NULL, note VARCHAR(500) NOT NULL DEFAULT '', request_key CHAR(36) NOT NULL, event_id BIGINT UNSIGNED, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(receiver_id,request_key), INDEX(purchase_order_id,received_at), FOREIGN KEY(purchase_order_id) REFERENCES repair_purchase_orders(id), FOREIGN KEY(receiver_id) REFERENCES repair_members(user_id), FOREIGN KEY(event_id) REFERENCES repair_ticket_events(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE repair_material_receipt_items (
 receipt_id BIGINT UNSIGNED NOT NULL, purchase_item_id BIGINT UNSIGNED NOT NULL, quantity INT NOT NULL, PRIMARY KEY(receipt_id,purchase_item_id),
 CHECK(quantity>0), FOREIGN KEY(receipt_id) REFERENCES repair_material_receipts(id), FOREIGN KEY(purchase_item_id) REFERENCES repair_purchase_items(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE repair_ocr_jobs (
 id CHAR(36) PRIMARY KEY, purchase_order_id BIGINT UNSIGNED NOT NULL, source_attachment_id CHAR(36) NOT NULL, source_sha256 CHAR(64) NOT NULL,
 requester_id BIGINT UNSIGNED NOT NULL, status VARCHAR(16) NOT NULL, provider VARCHAR(32) NOT NULL,
 attempts INT UNSIGNED NOT NULL DEFAULT 0, next_run_at DATETIME(3) NOT NULL, lease_token CHAR(36), lease_until DATETIME(3),
 result JSON, error_code VARCHAR(128), provider_request_id VARCHAR(128), version INT UNSIGNED NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX(status,next_run_at), INDEX(purchase_order_id,created_at), CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
 FOREIGN KEY(purchase_order_id) REFERENCES repair_purchase_orders(id), FOREIGN KEY(source_attachment_id) REFERENCES repair_attachments(id), FOREIGN KEY(requester_id) REFERENCES repair_members(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE repair_cost_confirmations (
 ticket_id BIGINT UNSIGNED PRIMARY KEY, labor_amount DECIMAL(14,2) NOT NULL, other_amount DECIMAL(14,2) NOT NULL,
 note VARCHAR(1000) NOT NULL DEFAULT '', confirmed_by BIGINT UNSIGNED NOT NULL, confirmed_at DATETIME(3) NOT NULL, version INT UNSIGNED NOT NULL DEFAULT 0,
 CHECK(labor_amount>=0), CHECK(other_amount>=0), FOREIGN KEY(ticket_id) REFERENCES repair_tickets(id), FOREIGN KEY(confirmed_by) REFERENCES repair_members(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE repair_attachments ADD CONSTRAINT fk_attachment_purchase FOREIGN KEY(purchase_order_id) REFERENCES repair_purchase_orders(id);
ALTER TABLE repair_assets ADD CONSTRAINT fk_asset_purchase_source FOREIGN KEY(source_purchase_item_id) REFERENCES repair_purchase_items(id);
ALTER TABLE repair_purchase_items ADD CONSTRAINT fk_item_ocr_source FOREIGN KEY(source_ocr_job_id) REFERENCES repair_ocr_jobs(id);
