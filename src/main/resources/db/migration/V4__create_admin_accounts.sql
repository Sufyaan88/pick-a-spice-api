CREATE TABLE admin_accounts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT admin_accounts_email_not_blank CHECK (length(btrim(email)) > 0),
    CONSTRAINT admin_accounts_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT admin_accounts_password_hash_not_blank CHECK (length(btrim(password_hash)) > 0)
);

CREATE UNIQUE INDEX admin_accounts_email_unique ON admin_accounts (lower(email));
