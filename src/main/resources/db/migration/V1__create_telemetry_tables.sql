-- Accepted telemetry measurements. Metrics are dynamic and stored as a JSONB object.
CREATE TABLE telemetry_metrics
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id          VARCHAR     NOT NULL,
    client_timestamp   TIMESTAMPTZ NOT NULL,
    server_received_at TIMESTAMPTZ NOT NULL,
    stats              JSONB       NOT NULL,
    CONSTRAINT uq_telemetry_metrics_device_client_ts UNIQUE (device_id, client_timestamp)
);

CREATE INDEX idx_telemetry_metrics_device_client_ts_desc
    ON telemetry_metrics (device_id, client_timestamp DESC);

-- Audit trail of every incoming message, including rejected ones.
CREATE TABLE device_message_history
(
    message_id         UUID PRIMARY KEY,
    device_id          VARCHAR     NOT NULL,
    client_timestamp   TIMESTAMPTZ NULL,
    server_received_at TIMESTAMPTZ NOT NULL,
    status             VARCHAR     NOT NULL,
    error_message      TEXT        NULL,
    payload            JSONB       NULL
);

CREATE INDEX idx_device_message_history_device_received_desc
    ON device_message_history (device_id, server_received_at DESC);

