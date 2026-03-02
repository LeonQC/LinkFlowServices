CREATE TABLE IF NOT EXISTS url_mapping (
                                           id BIGSERIAL PRIMARY KEY,
                                           long_url TEXT NOT NULL UNIQUE,
                                           slug VARCHAR(16) NOT NULL UNIQUE,
                                           created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
