CREATE TABLE IF NOT EXISTS public.order_drafts (
    telegram_chat_id BIGINT PRIMARY KEY,
    customer_id VARCHAR(20) NOT NULL REFERENCES public.customers(customer_id),
    customer_name VARCHAR(255) NOT NULL,
    customer_phone VARCHAR(30) NOT NULL,
    items JSONB NOT NULL DEFAULT '[]'::jsonb,
    unavailable_items JSONB NOT NULL DEFAULT '[]'::jsonb,
    total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    ready_to_confirm BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.order_drafts TO kasabot;
