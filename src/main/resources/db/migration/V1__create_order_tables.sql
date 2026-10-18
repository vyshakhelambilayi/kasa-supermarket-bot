CREATE TABLE IF NOT EXISTS public.orders (
    order_id BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(20) NOT NULL REFERENCES public.customers(customer_id),
    status VARCHAR(20) NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL CHECK (total_amount >= 0),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS public.order_items (
    order_item_id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES public.orders(order_id) ON DELETE CASCADE,
    item_number VARCHAR(20) NOT NULL REFERENCES public.inventory(item_number),
    item_name VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(10, 2) NOT NULL CHECK (unit_price >= 0),
    line_total NUMERIC(12, 2) NOT NULL CHECK (line_total >= 0)
);

-- Run as the database owner or an administrator after creating the tables.
GRANT SELECT (customer_id, name, phone_number) ON TABLE public.customers TO kasabot;
GRANT SELECT ON TABLE public.inventory TO kasabot;
GRANT UPDATE (quantity_available) ON TABLE public.inventory TO kasabot;
GRANT INSERT ON TABLE public.orders, public.order_items TO kasabot;
GRANT SELECT (order_id) ON TABLE public.orders TO kasabot;
GRANT USAGE, SELECT ON SEQUENCE public.orders_order_id_seq, public.order_items_order_item_id_seq TO kasabot;
