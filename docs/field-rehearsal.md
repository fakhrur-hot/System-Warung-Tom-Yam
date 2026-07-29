# Full-Day Field Rehearsal Checklist

> **Purpose**: A structured acceptance test for one real service day. ALL items must be
> checked off before declaring MVP ready. This rehearsal exercises every critical path —
> from customer ordering through payment, printing, closing, and reporting.
>
> **Prerequisites**: All chaos tests (Task 27) and pre-flight tests must PASS before
> attempting this rehearsal.

---

## Pre-day Setup

- [ ] QR table cards printed and placed on all tables
- [ ] Admin phone configured (connected, printers paired, menu loaded)
- [ ] Staff phone(s) configured (approved, GPS checked in)
- [ ] Bluetooth printer(s) powered and paired
- [ ] Supabase project active (not paused)
- [ ] Website accessible from customer phone browser
- [ ] OEM keep-alive setup completed on all devices (Task 28)

---

## Service Flow Tests

- [ ] Customer scans QR → menu loads in target language
- [ ] Customer places order → admin phone beeps within 3s
- [ ] Admin sends to kitchen → kitchen slip prints correctly
- [ ] Admin processes Cash payment → receipt prints with correct total
- [ ] Admin processes QR payment → receipt prints with correct total
- [ ] Staff places manual dine-in order → appears on admin Table View
- [ ] Staff sends to kitchen (if RBAC enabled) → kitchen slip prints
- [ ] Customer re-scans QR on occupied table → sees order status
- [ ] Customer cancels order (while RECEIVED) → admin Table View updates
- [ ] Admin cancels order → reason recorded, table freed
- [ ] Amendment: admin adds items to existing order → delta kitchen slip prints with "TAMBAHAN/ADDED"
- [ ] Admin resends to kitchen after amendment → only new items printed

---

## Multi-Language Check

- [ ] Customer PWA: switch language (BM, EN, ZH, TA, TH) → menu items display correctly
- [ ] Kitchen slip in correct print language
- [ ] Receipt in correct print language

---

## Resilience

- [ ] Phone screen off 10 minutes → send order → still beeps
- [ ] Staff phone loses signal briefly → pending order syncs on reconnect
- [ ] Print with printer powered off → error message shown (not crash)

---

## End-of-Day

- [ ] Admin: Sign Out with Closing → enter reason
- [ ] Closing report email received at configured address
- [ ] Dashboard metrics match paper till count
- [ ] Reports screen (APK) shows correct totals for the day
- [ ] All orders accounted for (completed + cancelled = total)

---

## Post-Day Verification

- [ ] Export database backup from APK → file saved successfully
- [ ] Supabase dashboard: verify aggregate numbers match
- [ ] No orphaned sessions (all tables FREE after closing)
- [ ] GitHub Release: new APK version accessible

---

## Punch List

> Anything that fails during the rehearsal becomes the post-MVP backlog.

- Issue 1: _______________
- Issue 2: _______________
- Issue 3: _______________

---

## Sign-off

- Date: _______________
- Tester: _______________
- Verdict: PASS / FAIL / PASS WITH CONDITIONS
- Notes: _______________

---

*Last updated: Task 30 — Phase 10 Hardening*
