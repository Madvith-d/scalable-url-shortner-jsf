ALTER TABLE short_urls
    ADD COLUMN activates_at TIMESTAMPTZ,
    ADD COLUMN max_clicks BIGINT,
    ADD COLUMN click_count BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_short_urls_max_clicks CHECK (max_clicks IS NULL OR max_clicks BETWEEN 1 AND 9007199254740991),
    ADD CONSTRAINT ck_short_urls_click_count CHECK (click_count >= 0),
    ADD CONSTRAINT ck_short_urls_activation_window CHECK (activates_at IS NULL OR expires_at IS NULL OR activates_at < expires_at);

-- Historical analytics were best-effort; preserve all clicks that were recorded.
UPDATE short_urls u SET click_count = (SELECT COUNT(*) FROM click_events c WHERE c.short_url_id = u.id);
