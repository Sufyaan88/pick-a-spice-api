CREATE TABLE products (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(5000),
    price NUMERIC(12, 2) NOT NULL,
    category_id BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    image_url VARCHAR(2048),
    image_alt_text VARCHAR(200),
    CONSTRAINT products_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT products_name_trimmed CHECK (name = btrim(name)),
    CONSTRAINT products_price_positive CHECK (price > 0),
    CONSTRAINT products_category_fk FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT products_image_alt_requires_url CHECK (image_alt_text IS NULL OR image_url IS NOT NULL)
);

CREATE UNIQUE INDEX products_name_unique ON products (lower(name));
CREATE INDEX products_category_id_index ON products (category_id);
