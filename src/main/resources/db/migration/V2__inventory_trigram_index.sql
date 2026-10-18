-- Requires pg_trgm to be enabled in kasadb.
CREATE INDEX IF NOT EXISTS inventory_item_name_trgm_idx
    ON public.inventory USING GIN (lower(item_name) gin_trgm_ops);
