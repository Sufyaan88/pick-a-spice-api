ALTER TABLE users ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD CONSTRAINT users_session_version_non_negative CHECK (session_version >= 0);
