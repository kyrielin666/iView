ALTER TABLE iview_device_alarm ADD COLUMN acknowledged_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE iview_device_alarm ADD COLUMN acknowledged_by VARCHAR(100);
ALTER TABLE iview_device_alarm ADD COLUMN acknowledgement VARCHAR(500);

ALTER TABLE iview_device_alarm ADD CONSTRAINT chk_alarm_acknowledgement
    CHECK ((acknowledged_at IS NULL AND acknowledged_by IS NULL AND acknowledgement IS NULL)
        OR (acknowledged_at IS NOT NULL AND acknowledged_by IS NOT NULL));

CREATE INDEX idx_iview_device_alarm_acknowledged ON iview_device_alarm(acknowledged_at, id);
