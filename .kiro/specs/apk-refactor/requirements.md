# Requirements Document

## Introduction

This document defines the requirements for the **APK Refactor** — a corrective redesign of the Warung Tom Yam POS Android app that addresses two classes of defect found in the 2026-07-22 APK audit (`docs/apk-audit-2026-07-22.md`):

1. **Data parsing correctness** — network/WebSocket payloads that "don't parse well," principally the `optString(name, null)` idiom that turns JSON `null` into the literal string `"null"`, duplicated across three separate hand-written parsers.
2. **Design/architecture structure** — UI and domain structure that doesn't map cleanly onto the app's functionality: two near-identical Table View stacks, order status modeled two incompatible ways at once, an overloaded admin-home overflow menu, an add/edit screen keyed on a magic empty string, and inconsistent dependency injection.

This is a **behavior-preserving refactor with bug fixes**. No new end-user features are introduced; the money path (order → kitchen → payment), attendance, printing, and reporting must behave identically after the refactor, except where current behavior is itself the bug (e.g. `"Note: null"` on slips).

## Glossary

- **Nullable_Field**: A JSON response field the backend may send as `null` or omit — e.g. `paymentMethod`, `sentToKitchenAt`, `cancelReason`, `cancelledBy`, order-item `note`, device `role`/`apiKey`, `lastSeenAt`.
- **Order_Mapper**: A single canonical function that converts an order JSON object into the domain/Room model, replacing the three current parsers.
- **Order_Status**: The lifecycle state of an order: `RECEIVED → SENT_TO_KITCHEN → PREPARING → READY → COMPLETED`, plus the terminal `CANCELLED`.
- **Table_View**: The color-coded grid of tables that is the primary POS surface, currently duplicated between the admin screen and the staff screen.
- **Staff_Permissions**: The RBAC flags (`canSendToKitchen`, `canTakePayment`, `canCancel`) that gate order actions on the staff role.
- **Production_DI**: The Hilt dependency graph used by the live app (as opposed to the `@Demo`-qualified graph from the demo-mode spec).
- **Minor_Units**: An integer monetary amount in the smallest currency unit (sen), e.g. `RM 12.50` stored as `1250`.

## Requirements

### Requirement 1: Null-safe parsing of nullable fields

**User Story:** As a stall operator, I want empty fields to show as empty, so that my kitchen slips and screens never display the word "null" and unpaid orders are correctly recognized as unpaid.

#### Acceptance Criteria

1. THE app SHALL provide a single JSON helper (e.g. `JSONObject.optStringOrNull(name)`) that returns Kotlin `null` both when a key is absent AND when the key is present with a JSON `null` value.
2. WHEN parsing any Nullable_Field, THE Order_Mapper and all other parsers SHALL use the null-safe helper and SHALL NOT use `optString(name, null)`.
3. WHEN an order item has no note, THE parsed `note` SHALL be `null`, and the item row and printed slip SHALL omit the note line entirely (no `"Note: null"`).
4. WHEN an order is unpaid, THE parsed `paymentMethod` SHALL be `null`, and any "is this order paid?" check SHALL treat it as unpaid.
5. WHEN a device is `PENDING`, THE parsed `apiKey`/`role` SHALL be `null`, and THE app SHALL NOT persist the string `"null"` to SecureStorage.
6. THE codebase SHALL contain zero remaining uses of the `optString(<name>, null)` pattern after this refactor (verifiable by grep).

### Requirement 2: A single canonical order parser

**User Story:** As a developer, I want one place that turns order JSON into the model, so that a backend field change or bug fix is made once, not three times.

#### Acceptance Criteria

1. THE app SHALL define one Order_Mapper (and one order-item mapper) used by: the REST catch-up sync, the Realtime `NEW_ORDER`/order-update handlers, and the Table View mapping.
2. THE previous per-call-site parsing blocks in `ApiClient`, `RealtimeService`, and `TableViewViewModel` SHALL be removed in favor of the Order_Mapper.
3. THE Order_Mapper SHALL be covered by unit tests, including a payload with JSON `null` in every Nullable_Field.

### Requirement 3: Resilient Realtime parsing

**User Story:** As a stall operator, I want an incoming order to still appear on my screen even if one field is unexpected, so that I never silently lose an order at the counter.

#### Acceptance Criteria

1. WHEN a Realtime message contains an order whose required fields are present, THE app SHALL persist and display that order even if an optional field is malformed or unknown.
2. IF a required field is missing or unparseable, THEN THE app SHALL log the specific field and message id and SHALL surface a non-fatal diagnostic, rather than discarding the message silently.
3. THE parse-failure path SHALL distinguish a transport/parse error from a business error so the UI does not show a generic message for both.

### Requirement 4: One typed Order_Status model with an explicit state machine

**User Story:** As a developer, I want a single definition of order states and the transitions between them, so that action buttons and grid colors can't disagree and typos are caught at compile time.

#### Acceptance Criteria

1. THE app SHALL define one `enum class OrderStatus` covering `RECEIVED`, `SENT_TO_KITCHEN`, `PREPARING`, `READY`, `COMPLETED`, `CANCELLED`.
2. THE app SHALL provide a Room `TypeConverter` for Order_Status and SHALL store status via the enum, not a raw string.
3. WHEN mapping order JSON, an unrecognized status string SHALL be mapped to a defined `UNKNOWN` case (or rejected) rather than silently accepted.
4. THE allowed actions (send-to-kitchen, payment, cancel) SHALL be derived from Order_Status transition rules in one place, and both the admin and staff detail sheets SHALL consume that logic rather than comparing raw strings.
5. THE grid cell color SHALL be derived from Order_Status in one place shared by both roles.

### Requirement 5: A single shared Table View

**User Story:** As a developer, I want the admin and staff table grids to be the same component, so that a fix to a color, status, or action reaches both roles.

#### Acceptance Criteria

1. THE app SHALL provide one reusable Table View composable (grid + cell) and one reusable order-detail sheet, parameterized by Staff_Permissions (admin = all permissions granted).
2. THE admin home and the staff ordering screen SHALL both render the shared Table View; the duplicate `TableCell`/`StaffTableCell` and the duplicated detail sheets SHALL be removed.
3. THE shared components SHALL preserve each role's current behavior: admin retains session lifecycle, daily-availability popup, and table management; staff retains the offline-pending banner, check-out, and RBAC gating.
4. Common view-model state types currently duplicated (`TableState`, `TableStatus`, `OrderDetailState`) SHALL be defined once and shared.

### Requirement 6: Admin home information architecture

**User Story:** As a stall owner, I want the most common action to be obvious and destructive actions to be separated, so that running the stall is fast and I don't sign out by accident.

#### Acceptance Criteria

1. THE admin home SHALL expose "New Dine-In Order" as a primary, visible action (consistent with the staff screen's FAB), not buried in an overflow menu.
2. THE top-bar affordance for creating an order SHALL not be an icon whose conventional meaning is a different action; table management SHALL have its own clearly-labeled entry point.
3. Setup/config destinations (Devices, Printers, Menu Management, Generate QR Cards, Backup, Background Setup, Settings) SHALL be grouped under a clearly-labeled section, separate from operational actions.
4. THE two sign-out actions (Sign Out, Sign Out with Closing) SHALL be visually separated from navigation items and retain their existing confirmation/closing flows.

### Requirement 7: Explicit Add vs. Edit menu-item mode

**User Story:** As a developer, I want the add and edit flows to be explicitly distinguished, so that the mode is not inferred from an empty string.

#### Acceptance Criteria

1. THE menu-item screen SHALL receive an explicit mode (add vs. edit) via a typed nav argument or distinct routes, not an empty `category` string.
2. WHEN editing, THE screen SHALL load the existing item by id and prefill all fields including its category.
3. WHEN adding, THE screen SHALL accept a pre-selected category and SHALL NOT depend on empty-string sentinels.

### Requirement 8: Consistent dependency injection

**User Story:** As a developer, I want one DI style, so that dependencies aren't wired in one path and forgotten in another.

#### Acceptance Criteria

1. THE ordering-role ViewModel SHALL be a `@HiltViewModel` and its screen SHALL obtain it via `hiltViewModel()`, consistent with all other screens.
2. THE ordering foreground service and realtime service SHALL obtain dependencies via Hilt (`@AndroidEntryPoint`) rather than a hand-rolled service locator, OR the service-locator SHALL be documented and confined to services that genuinely cannot use Hilt, with justification.
3. Production_DI behavior (singletons, scopes) SHALL be unchanged from the user's perspective after the refactor.

### Requirement 9: Status color semantics and legend

**User Story:** As a stall operator, I want the table colors to match urgency and be explained, so that I can read the grid at a glance.

#### Acceptance Criteria

1. THE color assigned to each Order_Status SHALL reflect operational urgency, with the highest-attention color assigned to the state that most needs staff action (`READY`).
2. THE Table View SHALL show a legend or rely on the per-cell status label so the color→state mapping is not left implicit.
3. THE color mapping SHALL be defined once (Requirement 4/5) and used by both roles.

### Requirement 10: Monetary values as integer minor units (deferred/optional)

**User Story:** As a stall owner, I want totals to be exact, so that cash and QR reconciliation never drifts by a cent.

#### Acceptance Criteria

1. THE domain model SHOULD represent prices and totals as Minor_Units (integer sen) rather than `Double`.
2. IF this migration is undertaken, THEN a Room migration SHALL convert existing `Double` price/total columns without data loss, and all display formatting SHALL convert Minor_Units to `RM x.xx` at the UI edge.
3. This requirement MAY be deferred to a later phase; if deferred, THE plan SHALL clearly mark it as not-in-this-pass so the omission is intentional, not accidental.

### Requirement 11: Food dictionary cleanup

**User Story:** As a stall owner entering my menu, I want the translation suggestions to be sensible, so that I'm not offered nonsense terms.

#### Acceptance Criteria

1. THE food dictionary SHALL NOT contain non-food/joke entries (e.g. "Racun"/poison, "Haram"/forbidden) that surface as menu autocomplete suggestions.
2. WHERE dictionary entries are transliterations rather than translations, THE behavior SHALL remain the documented English-fallback design; no correctness requirement is imposed on translation quality beyond removing clearly inappropriate entries.

### Requirement 12: No regressions

**User Story:** As a stall owner, I want the refactor to change nothing I can see except the bug fixes, so that my daily operation is uninterrupted.

#### Acceptance Criteria

1. THE debug and release APKs SHALL build (`./gradlew assembleDebug`) after each phase checkpoint.
2. THE existing behavior of printing, attendance, reports, backup, and the demo-mode spec SHALL be preserved.
3. Each phase SHALL be independently shippable and SHALL leave the app in a working state.
