ALTER TABLE categories ADD COLUMN parent_id BIGINT;

ALTER TABLE categories
    ADD CONSTRAINT categories_parent_fk FOREIGN KEY (parent_id) REFERENCES categories (id),
    ADD CONSTRAINT categories_not_own_parent CHECK (parent_id <> id);

CREATE INDEX categories_parent_id_index ON categories (parent_id);
