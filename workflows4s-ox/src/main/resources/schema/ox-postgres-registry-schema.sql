-- Workflow registry table for OxPostgresRegistry
-- Tracks workflow instances with execution status, timestamps, and custom tags

CREATE TABLE IF NOT EXISTS workflow_registry (
    instance_id TEXT      NOT NULL,
    template_id TEXT      NOT NULL,
    status      TEXT      NOT NULL CHECK (status IN ('Running', 'Awaiting', 'Finished')),
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    wakeup_at   TIMESTAMP,
    tags        JSONB,
    PRIMARY KEY (template_id, instance_id)
);

-- Index for finding stale workflows
CREATE INDEX IF NOT EXISTS idx_workflow_registry_status_updated
    ON workflow_registry (status, updated_at);

-- Index for finding workflows with pending wakeups
CREATE INDEX IF NOT EXISTS idx_workflow_registry_wakeup
    ON workflow_registry (wakeup_at)
    WHERE wakeup_at IS NOT NULL;

-- GIN index for JSONB tag queries (supports @> containment operator)
CREATE INDEX IF NOT EXISTS idx_workflow_registry_tags
    ON workflow_registry USING GIN (tags);

-- Index for timestamp-based queries
CREATE INDEX IF NOT EXISTS idx_workflow_registry_created
    ON workflow_registry (created_at);

COMMENT ON TABLE workflow_registry IS
    'Tracks workflow instances for the OxPostgresRegistry';
COMMENT ON COLUMN workflow_registry.status IS
    'Running: actively executing, Awaiting: waiting for signal, Finished: completed';
COMMENT ON COLUMN workflow_registry.wakeup_at IS
    'Timestamp when workflow should be woken up for timers or retries';
COMMENT ON COLUMN workflow_registry.tags IS
    'Custom metadata extracted from workflow state via Tagger interface';
