-- Story 104470: App Version Management / Version History – Backend
-- ASSUMPTION: not found in repo content
--
-- Minimal schema additions to support app version history and restore.
-- Namespaced under dpai to match existing objects.

CREATE TABLE IF NOT EXISTS dpai.apps (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    current_version INTEGER NOT NULL DEFAULT 1,
    config_json     TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS dpai.app_versions (
    app_id         TEXT NOT NULL REFERENCES dpai.apps(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    config_json    TEXT NOT NULL,
    created_by     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted        BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (app_id, version_number)
);

CREATE INDEX IF NOT EXISTS idx_app_versions_app_id_version_desc
    ON dpai.app_versions(app_id, version_number DESC);
