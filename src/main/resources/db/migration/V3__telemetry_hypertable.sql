-- Turn telemetry_metrics into a TimescaleDB hypertable partitioned by client_timestamp.
-- Idempotent: does nothing if the table is already a hypertable.
CREATE EXTENSION IF NOT EXISTS timescaledb;

DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1
                       FROM timescaledb_information.hypertables
                       WHERE hypertable_schema = 'public'
                         AND hypertable_name = 'telemetry_metrics') THEN
            -- A hypertable's unique indexes must include the partitioning column.
            ALTER TABLE telemetry_metrics DROP CONSTRAINT telemetry_metrics_pkey;
            ALTER TABLE telemetry_metrics ADD PRIMARY KEY (id, client_timestamp);
            PERFORM create_hypertable('telemetry_metrics', 'client_timestamp',
                                      chunk_time_interval => INTERVAL '1 day',
                                      migrate_data => TRUE);
        END IF;
    END
$$;

