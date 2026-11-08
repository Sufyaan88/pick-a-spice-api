ALTER TABLE admin_accounts RENAME TO users;

ALTER TABLE users ADD COLUMN role VARCHAR(20);
UPDATE users SET role = 'ADMIN';
ALTER TABLE users ALTER COLUMN role SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_role_valid CHECK (role IN ('ADMIN'));

ALTER TABLE users RENAME CONSTRAINT admin_accounts_pkey TO users_pkey;
ALTER TABLE users RENAME CONSTRAINT admin_accounts_email_not_blank TO users_email_not_blank;
ALTER TABLE users RENAME CONSTRAINT admin_accounts_email_normalized TO users_email_normalized;
ALTER TABLE users RENAME CONSTRAINT admin_accounts_password_hash_not_blank TO users_password_hash_not_blank;
ALTER INDEX admin_accounts_email_unique RENAME TO users_email_unique;
ALTER SEQUENCE admin_accounts_id_seq RENAME TO users_id_seq;
