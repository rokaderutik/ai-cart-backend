CREATE TABLE menu_item (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description TEXT NOT NULL,
    category VARCHAR(60) NOT NULL,
    ingredients TEXT[] NOT NULL,
    dietary_tags TEXT[] NOT NULL,
    spice_level INTEGER NOT NULL CHECK (spice_level BETWEEN 0 AND 5),
    price NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'INR'),
    available BOOLEAN NOT NULL
);

CREATE TABLE guest_session (
    id VARCHAR(44) PRIMARY KEY CHECK (id ~ '^SESSION_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    context_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_guest_session_expires_at ON guest_session (expires_at);

CREATE TABLE cart (
    id VARCHAR(41) PRIMARY KEY CHECK (id ~ '^CART_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    session_id VARCHAR(44) NOT NULL UNIQUE REFERENCES guest_session (id) ON DELETE CASCADE
);

CREATE TABLE cart_item (
    id UUID PRIMARY KEY,
    cart_id VARCHAR(41) NOT NULL REFERENCES cart (id) ON DELETE CASCADE,
    menu_item_id UUID NOT NULL REFERENCES menu_item (id),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 20),
    CONSTRAINT uk_cart_item_cart_menu UNIQUE (cart_id, menu_item_id)
);
