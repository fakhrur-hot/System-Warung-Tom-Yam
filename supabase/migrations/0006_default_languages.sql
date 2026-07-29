-- Café-wide default UI language per surface. Each client (admin app, ordering-staff app,
-- customer website) applies the relevant default ONLY when the device/browser has no
-- locally-saved language choice yet; once the user picks a language on their own device,
-- that choice wins. Values: BM/EN/ZH/TA/TH. Default seed is BM (Bahasa Malaysia).
INSERT INTO settings (key, value) VALUES
  ('default_lang_admin',    'BM'),
  ('default_lang_ordering', 'BM'),
  ('default_lang_customer', 'BM')
ON CONFLICT (key) DO NOTHING;
