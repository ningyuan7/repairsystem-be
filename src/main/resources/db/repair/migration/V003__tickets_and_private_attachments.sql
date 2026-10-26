CREATE TABLE repair_tickets (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, ticket_no VARCHAR(32) NOT NULL UNIQUE,
 store_id BIGINT UNSIGNED NOT NULL, reporter_id BIGINT UNSIGNED NOT NULL, asset_id BIGINT UNSIGNED, replacement_asset_id BIGINT UNSIGNED,
 category_id BIGINT UNSIGNED NOT NULL, title VARCHAR(100) NOT NULL, description VARCHAR(2000) NOT NULL, facility_snapshot JSON NOT NULL,
 contact_name VARCHAR(64) NOT NULL, contact_phone VARCHAR(32) NOT NULL, priority VARCHAR(16) NOT NULL,
 status VARCHAR(32) NOT NULL, resolution_plan VARCHAR(32), procurement_required BOOLEAN NOT NULL DEFAULT FALSE,
 assignee_id BIGINT UNSIGNED, assigned_at DATETIME(3), accepted_at DATETIME(3), first_started_at DATETIME(3),
 completed_repair_at DATETIME(3), submitted_for_acceptance_at DATETIME(3), completed_at DATETIME(3),
 submitted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), version INT UNSIGNED NOT NULL DEFAULT 0,
 CHECK(priority IN ('NORMAL','URGENT')),
 CHECK(status IN ('SUBMITTED','WAITING_PROCUREMENT','READY_FOR_DISPATCH','ASSIGNED','ACCEPTED','IN_PROGRESS','WAITING_ACCEPTANCE','COMPLETED','CANCELLED','REJECTED')),
 CHECK(resolution_plan IS NULL OR resolution_plan IN ('REPAIR','PART_REPAIR','REPLACEMENT')),
 INDEX(store_id,status,submitted_at,id), INDEX(assignee_id,status,assigned_at,id), INDEX(category_id,submitted_at), INDEX(completed_at,id),
 FOREIGN KEY(store_id) REFERENCES repair_stores(id), FOREIGN KEY(reporter_id) REFERENCES repair_members(user_id),
 FOREIGN KEY(assignee_id) REFERENCES repair_members(user_id), FOREIGN KEY(asset_id) REFERENCES repair_assets(id),
 FOREIGN KEY(replacement_asset_id) REFERENCES repair_assets(id), FOREIGN KEY(category_id) REFERENCES repair_categories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE repair_active_assets (
 asset_id BIGINT UNSIGNED PRIMARY KEY, ticket_id BIGINT UNSIGNED NOT NULL UNIQUE,
 FOREIGN KEY(asset_id) REFERENCES repair_assets(id), FOREIGN KEY(ticket_id) REFERENCES repair_tickets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE repair_ticket_events (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, ticket_id BIGINT UNSIGNED NOT NULL, ticket_version INT UNSIGNED NOT NULL,
 action VARCHAR(64) NOT NULL, from_status VARCHAR(32), to_status VARCHAR(32) NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL, actor_role VARCHAR(32) NOT NULL, visibility VARCHAR(24) NOT NULL,
 note VARCHAR(2000) NOT NULL DEFAULT '', payload JSON NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(ticket_id,ticket_version), INDEX(ticket_id,id), CHECK(visibility IN ('PARTICIPANTS','HQ_ONLY')),
 FOREIGN KEY(ticket_id) REFERENCES repair_tickets(id), FOREIGN KEY(actor_id) REFERENCES repair_members(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE repair_attachments (
 id CHAR(36) PRIMARY KEY, uploader_id BIGINT UNSIGNED NOT NULL, store_id BIGINT UNSIGNED, ticket_id BIGINT UNSIGNED,
 purchase_order_id BIGINT UNSIGNED, purpose VARCHAR(32) NOT NULL, storage_backend VARCHAR(16) NOT NULL DEFAULT 'LOCAL',
 object_key VARCHAR(512) NOT NULL, content_type VARCHAR(64) NOT NULL, size INT UNSIGNED NOT NULL, sha256 CHAR(64) NOT NULL,
 status VARCHAR(16) NOT NULL, expires_at DATETIME(3) NOT NULL, bound_event_id BIGINT UNSIGNED,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(storage_backend,object_key), INDEX(uploader_id,status,expires_at), INDEX(ticket_id,purpose),
 CHECK(purpose IN ('FAULT','ORDER_SCREENSHOT','WORK_RECORD','RESULT','RECEIPT','AVATAR')),
 CHECK(status IN ('STAGED','BOUND','DELETING','DELETED','EXPIRED')),
 FOREIGN KEY(uploader_id) REFERENCES repair_members(user_id), FOREIGN KEY(store_id) REFERENCES repair_stores(id),
 FOREIGN KEY(ticket_id) REFERENCES repair_tickets(id), FOREIGN KEY(bound_event_id) REFERENCES repair_ticket_events(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE repair_notifications (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, recipient_id BIGINT UNSIGNED NOT NULL, event_id BIGINT UNSIGNED NOT NULL,
 type VARCHAR(64) NOT NULL, message VARCHAR(500) NOT NULL, ticket_id BIGINT UNSIGNED NOT NULL,
 is_read BOOLEAN NOT NULL DEFAULT FALSE, read_at DATETIME(3), created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE(recipient_id,event_id,type), INDEX(recipient_id,is_read,id),
 FOREIGN KEY(recipient_id) REFERENCES repair_members(user_id), FOREIGN KEY(event_id) REFERENCES repair_ticket_events(id), FOREIGN KEY(ticket_id) REFERENCES repair_tickets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
