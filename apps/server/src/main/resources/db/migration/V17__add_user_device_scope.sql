CREATE TABLE iview_user_device_scope (
    user_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, device_id),
    FOREIGN KEY (user_id) REFERENCES iview_user(id) ON DELETE CASCADE,
    FOREIGN KEY (device_id) REFERENCES iview_device(id) ON DELETE CASCADE
);
CREATE INDEX idx_iview_user_device_scope_device ON iview_user_device_scope(device_id);
