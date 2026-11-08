ALTER TABLE users ADD COLUMN locked_until TIMESTAMP WITH TIME ZONE;

CREATE TABLE login_failures (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX login_failures_user_time_index ON login_failures (user_id, attempted_at);
