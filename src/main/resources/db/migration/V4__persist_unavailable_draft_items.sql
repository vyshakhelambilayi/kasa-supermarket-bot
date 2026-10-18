ALTER TABLE public.order_drafts
    ADD COLUMN IF NOT EXISTS unavailable_items JSONB NOT NULL DEFAULT '[]'::jsonb;
