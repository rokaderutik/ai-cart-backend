ALTER TABLE menu_item ADD COLUMN source_key VARCHAR(120) UNIQUE;
ALTER TABLE menu_item ADD COLUMN source_active BOOLEAN NOT NULL DEFAULT true;

UPDATE menu_item SET source_key = CASE id
    WHEN 1 THEN 'paneer-butter-masala'
    WHEN 2 THEN 'dal-tadka'
    WHEN 3 THEN 'chicken-biryani'
    WHEN 4 THEN 'vegetable-pulao'
    WHEN 5 THEN 'butter-naan'
    WHEN 6 THEN 'gulab-jamun'
END WHERE id BETWEEN 1 AND 6;

CREATE TABLE vector_store (
    id UUID PRIMARY KEY,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL,
    embedding public.vector(768) NOT NULL
);

CREATE TABLE knowledge_index_state (
    corpus_id VARCHAR(80) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    embedding_fingerprint TEXT,
    source_hash VARCHAR(64),
    published_at TIMESTAMP WITH TIME ZONE
);
INSERT INTO knowledge_index_state (corpus_id) VALUES ('restaurant-v1');
