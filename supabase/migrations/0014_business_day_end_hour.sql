-- The business day gets an end.
--
-- `business_day_start_hour` already anchors reports for late-night cafés: a stall opening at 3 PM
-- and closing at 2 AM belongs to one trading day, not two calendar dates. But there was no closing
-- hour, so nothing could answer "is the café open right now?" — only "which day does this order
-- belong to".
--
-- That question is what staff auto-logout needs: a device should not stay signed in to a café that
-- shut hours ago, on a phone that went home in somebody's pocket.
--
-- Seeded to 2 (2 AM) rather than something tidier because it must be consistent with the existing
-- start default of 15 (3 PM) — a start after the end is what a café that trades past midnight looks
-- like, and the pair has to make sense on day one for a café that never opens this screen.
INSERT INTO settings (key, value)
VALUES ('business_day_end_hour', '2')
ON CONFLICT (key) DO NOTHING;
