-- Staff QR-only payment mode.
--
-- `staff_can_take_payment` already gates whether ordering staff can take payment at all. This adds
-- a second, narrower gate: when a café's staff device force-hides "Pay Cash" and only shows "Pay
-- QR", for cafés that want every staff-collected sale to leave a QR-payment trail rather than
-- physical cash a staff member could pocket.
--
-- Deliberately independent of `staff_can_take_payment` rather than replacing its two values with a
-- three-way enum: the admin device's own payment buttons (StaffPermissions.ADMIN) are never
-- affected by this setting, only ordering-staff sessions are — see StaffPermissions.qrOnly.
--
-- Seeded to false so an upgrading café's staff keep seeing both Pay Cash and Pay QR exactly as
-- before, matching how `staff_can_take_payment` itself defaults to false.
INSERT INTO settings (key, value)
VALUES ('staff_qr_only', 'false')
ON CONFLICT (key) DO NOTHING;
