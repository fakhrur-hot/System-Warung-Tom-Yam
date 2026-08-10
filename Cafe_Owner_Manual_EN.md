# Warung Tom Yam POS - Cafe Owner Manual

## Table of Contents

1. [Getting Started](#1-getting-started)
2. [Main Screen - Table View](#2-main-screen---table-view)
3. [Cafe Management](#3-cafe-management)
4. [Settings](#4-settings)
5. [Devices & Staff](#5-devices--staff)
6. [Payment](#6-payment)
7. [Reports](#7-reports)
8. [Cash Drawer](#8-cash-drawer)
9. [Backup & Restore](#9-backup--restore)
10. [Payment Monitor](#10-payment-monitor)
11. [Payment Gateway](#11-payment-gateway)
12. [Operating Modes](#12-operating-modes)
13. [Troubleshooting](#13-troubleshooting)

---

## 1. Getting Started

### First-Time Setup

1. **Open the app** - the permission screen appears.
   - Allow **Location**, **Nearby Devices**, and **Notifications**.
   - Tap "Allow" for each permission.

2. **Google Sign-In** (Cloud Mode only)
   - Tap "Sign in with Google" and select your account.
   - If you already have a cafe, select it from the list.
   - If new cafe, tap "Set up this cafe."

> [!] **TESTING MODE:** Google Sign-In and Google Drive are still in testing.
> Only registered tester accounts can use these features at this time.

3. **Select Mode** on the Role Select screen:
   - **QR Ordering Mode** - for cafes using customer QR ordering
   - **Wireless AP Mode** - LAN without internet
   - **Kiosk Mode** - counter without tables

4. **Setup Wizard** (if new cafe)
   - Select Operating Mode (Cloud / LAN / Kiosk)
   - Enter cafe details (Cafe Name, URL, etc.)
   - Tap **Save**

### Language

Tap the **Globe icon** at the top-right corner on any screen to switch language:
- BM (Bahasa Melayu)
- EN (English)
- ZH (Chinese)
- TA (Tamil)
- TH (Thai)

### Theme

Tap the **Palette icon** to select a visual theme:
- Bold, Edgy, Elegant, Luxury, Minimalist, Soft

---

## 2. Main Screen - Table View

After login, you will see a **table grid** colored by order status:

| Color | Meaning |
|-------|---------|
| Green | TABLE FREE - ready for customers |
| Red | OCCUPIED - active order in progress |
| Blue | SENT TO KITCHEN - order being prepared |
| Yellow | READY - food ready to serve |

### Table Actions

- **Tap a free table** - Opens a new order sheet
- **Tap an occupied table** - Opens order detail (pay, add items, send to kitchen)

### Dashboard (Swipe Left Page)

Swipe **left** from the Table Grid to see the **Dashboard** - today's performance overview.

#### KPI Cards (5 cards at top)

| Card | Meaning | Color |
|------|---------|-------|
| **Revenue** | Today's total income (RM) | Green |
| **Orders** | Order count today (+ yesterday comparison) | Blue |
| **Avg** | Average order value (RM) | Purple |
| **Active** | Currently active orders (unpaid) | Yellow |
| **Cancelled** | Cancelled count + percentage | Red if >10%, grey if low |

#### Charts

| Chart | What It Shows |
|-------|---------------|
| **Hourly Revenue** (line) | Revenue by hour - spot peak times |
| **Daily Trend** (line) | Daily revenue over recent days - spot trends |
| **Payment Methods** (donut) | Payment method split (Cash vs QR vs Gateway) |
| **Best Sellers** (bar) | Top selling items today |

#### How to Read the Dashboard

- **Revenue up, Orders up** - Good day, more customers
- **Revenue up, Orders flat** - Average order value increasing (customers ordering more)
- **Active high** - Many tables unpaid - may need more staff
- **Cancelled high (>10%)** - Check stock or investigate cancellation reasons
- **Hourly peak** - Know your busiest hours to prepare more ingredients

### Main Buttons

| Button | Function |
|--------|----------|
| **+** (FAB bottom-right) | Create new dine-in order |
| **Calculator** (FAB bottom-left) | Quick calculator |
| **(...) Overflow menu** | Open full navigation menu |

### Overflow Menu (...)

> [!] Requires **PIN Lock** if enabled.

| Item | Function |
|------|----------|
| Pending Kitchen Prints | View prints not yet sent |
| Recent Prints | Recent print history |
| Cafe Management | Manage profile, menu, tables, QR |
| Devices & Staff | Manage devices & staff invitations |
| Payment Monitor | E-wallet notification monitor |
| Notification Listener | Toggle on/off (Switch) |
| Drawer | Open cash drawer screen |
| Reports | Sales reports |
| Backup | Export/Import data |
| Settings | All settings |
| Sign Out | Close session |
| Sign Out with Closing | Close session + closing report |

---

## 3. Cafe Management

Access: **Menu (...) > Cafe Management**

### 3.1 Cafe Profile

| Field/Button | Description |
|-------------|-------------|
| **Cafe Name** (text field) | Name shown on receipts & website |
| **Logo** (Pick Logo / Change Logo) | Upload cafe logo (image file) |
| **Capture Location** (button) | Capture GPS coordinates of cafe location |
| **Radius** (number field) | Maximum distance staff can check-in (meters) |
| **Timezone** | Time zone displayed (auto-detect) |
| **Payment QR** (Upload/Replace/Remove) | E-wallet payment QR code for display |
| **Menu Preset** (Load Preset) | Load a ready-made menu template |
| **Cancel / Save** (bottom bar) | Discard or save changes |

---

#### 3.1.1 Uploading Cafe Logo

The cafe logo appears in **three places**:
- On **printed receipts** (top of thermal receipt)
- On **table QR cards** (if Logo mode is selected during QR generation)
- On the **customer digital menu** (QR ordering website)

**Steps:**
1. Open **Cafe Management > Cafe Profile**
2. Tap the **"Pick Logo"** button (or **"Change Logo"** if one already exists)
3. Select an image from your device gallery
4. Logo preview is shown - make sure it looks clear at small size
5. Tap **Save** on the bottom bar to save

> [i] **Tips for receipt logo:**
> - Use a **black-and-white or high contrast** image - thermal printers don't print color
> - Recommended size: **300x300 pixels** minimum
> - Logo is auto-cropped to square
> - Format: JPG or PNG

> [i] **Note:** The same logo is used for receipts AND table QR cards. If you want
> different branding on QR cards, use "Text" mode (cafe name only) in Generate Table QR.

#### 3.1.2 Logo Locations Summary

| Location | Logo Source | How to Change |
|----------|-------------|---------------|
| Printed receipt | Logo from Cafe Profile | Change at Cafe Profile > Pick Logo > Save |
| Table QR card | Logo from Cafe Profile OR text cafe name | Select mode in Generate Table QR screen |
| Customer online menu | Logo from Cafe Profile | Same - change in Cafe Profile |

### 3.2 Menu Management

**Access:** Cafe Management > Menu Management

---

#### 3.2.1 Creating a New Category

1. Tap the **Folder icon** in the top bar
2. Enter the category name (e.g., "RICE", "DRINKS", "SEAFOOD")
3. Tap **OK**
4. New category appears as a new tab

#### 3.2.2 Setting Kitchen Slip Route for a Category

Each category has a **Kitchen Slip Route** that determines which printer prints items
in that category. This lets you route food orders to the kitchen and drinks to the bar.

1. **Long-press** on the category tab
2. Select **Edit** from the popup menu
3. In the Category Editor dialog:
   - Enter display names in multiple languages:
     - English
     - Bahasa Melayu
     - Chinese
     - Tamil
     - Thai
   - Select **Kitchen Slip Route**:
     - **Food** - Items in this category print on the FOOD kitchen slip
     - **Beverage** - Items in this category print on the BEVERAGE kitchen slip
4. Tap **Save**

> [i] **Example Setup:**
> - Categories "RICE", "SIDE DISHES", "FRIED" > Route: **Food** > prints on kitchen printer
> - Categories "DRINKS", "ICED", "HOT" > Route: **Beverage** > prints on bar printer

> [i] **Note:** If you only have one kitchen printer, both routes (Food & Beverage) will
> print on the same printer. Route only matters if you have two separate printers.

#### 3.2.3 Reordering Categories

1. Tap the **Reorder icon** in the top bar
2. Use up/down buttons to rearrange category order
3. This order determines the tab sequence on the ordering screen

#### 3.2.4 Sorting Items Within a Category

1. Tap the **Sort icon** in the top bar
2. Choose **Auto-sort** (alphabetical A-Z) or manual sort with up/down buttons

#### 3.2.5 Deleting a Category

1. **Long-press** on the category tab
2. Select **Delete**
3. Warning: All items in that category will become uncategorized

---

#### 3.2.6 Adding a New Menu Item

1. Select the desired category tab
2. Tap **+** (FAB) or the Add icon in the top bar
3. Fill in the item form:

**Step 1 - Select Category:**
- Choose a category from the list
- Or tap "New category" to create one inline

**Step 2 - Fill Item Details:**

| Field | Description | Required? |
|-------|-------------|-----------|
| **Category** | Primary category for the item (already selected) | Yes |
| **Also show in** (FilterChips) | Show this item in additional categories too | No |
| **Code** | Short code (for quick search) | No |
| **Name BM** | Name in Bahasa Melayu | Yes |
| **+ Other Languages** (expandable) | Add names in EN, ZH, Tamil, Thai | No |

**Pricing Mode:**

| Mode | When to Use | What to Fill |
|------|-------------|--------------|
| **Single Price** | Item with one size/price only | Enter price (e.g., RM 8.00) |
| **Multiple Price** | Item with S/M/L sizes | Enter 3 prices: Small, Medium, Large |
| **Market Price** | Price changes daily (e.g., fish, prawns) | No price entered - asked each day |

**Multiple Price Example:**
```
Small (S):  RM 3.00   - Small Iced Tea
Medium (M): RM 4.50   - Medium Iced Tea
Large (L):  RM 6.00   - Large Iced Tea
```

**Additional Toggles:**

| Toggle | Function |
|--------|----------|
| **Do not translate** (Switch) | Item name won't be auto-translated to other languages |
| **Ask me daily** (Switch) | Popup will ask daily whether this item is available today |

**Item Image:**
- Tap the image button to upload a photo of the item
- This image appears on the customer digital menu (QR ordering)

4. Tap **Save**

---

#### 3.2.7 Editing a Menu Item

1. On the item list, **swipe left** on the item
2. Tap the **Edit** (pencil) icon
3. Change any fields as needed
4. Tap **Save**

#### 3.2.8 Deleting a Menu Item

1. **Swipe left** on the item
2. Tap the **Delete** (red trash) icon
3. Confirm deletion in the dialog

#### 3.2.9 Toggle Item Availability

- Each item has a **Switch** on the right side
- **ON** = available today (shown on menu)
- **OFF** = unavailable (hidden from customers, greyed on admin)

> [i] Useful for items that are out of stock today - turn off without deleting.

---

#### 3.2.10 Example Warung Menu Setup

```
RICE (Route: Food)
   Nasi Putih          RM 2.00  (Single Price)
   Nasi Goreng         RM 8.00  (Single Price)
   Nasi Lemak          RM 6.00 / 8.00 / 10.00 (Multiple: S/M/L)

SIDE DISHES (Route: Food)
   Ayam Goreng         RM 5.00  (Single Price)
   Ikan Bakar          Market Price (asked daily)
   Udang Masak Lemak   Market Price

DRINKS (Route: Beverage)
   Teh Ais             RM 3.00 / 4.50 / 6.00 (S/M/L)
   Kopi O              RM 2.50  (Single Price)
   Plain Water         RM 0.00  (Single Price, free)
```

### 3.3 Tables Management

> Only available in Table Service mode (not in Kiosk)

**Access:** Cafe Management > Tables Management

---

#### 3.3.1 Adding a New Table

1. In the **Label** field, enter a table name/label (e.g., "Table 1", "VIP", "Outdoor")
2. Tap **Add**
3. Table is added with an auto-generated ID (T0001, T0002, ...)

> [i] Table IDs are auto-generated and cannot be changed. The label is the displayed name.

**Limit:** Maximum tables depends on your plan (see counter on screen).

#### 3.3.2 Adding Take-Out Slots

1. In the **Take-out** section, tap **Add Take-out**
2. Take-out slots appear separately from dine-in tables
3. Use for customers taking away - no table QR needed

#### 3.3.3 Renaming a Table

1. Tap the **Edit (pencil) icon** on the right side of the table
2. The label field becomes editable (inline)
3. Change the name, then tap **Save**
4. Or tap **Cancel** to discard

#### 3.3.4 Deleting a Table

1. Tap the **Delete (red trash) icon** on the right side of the table
2. Table is deleted immediately

> [!] **Warning:** Do not delete tables that have active orders. Complete orders first.

#### 3.3.5 Information Shown Per Table

| Info | Description |
|------|-------------|
| **Label** | Table name (editable) |
| **ID** | Auto-generated unique code (T0001, etc.) - cannot be changed |

#### 3.3.6 Example Table Setup

```
Dine-In (12 tables):
   T0001 - "Table 1"
   T0002 - "Table 2"
   ...
   T0010 - "Table 10"
   T0011 - "VIP 1"
   T0012 - "VIP 2"

Take-Out (3 slots):
   TW001 - "Takeaway 1"
   TW002 - "Takeaway 2"
   TW003 - "Takeaway 3"
```

### 3.4 Generate Table QR (Print Table QR Cards)

> Only available if web ordering is active (Cloud Mode with website)

**Access:** Cafe Management > Generate Table QR

Generates table QR cards in PDF format (A6 per card, 4 cards per A4 page) for printing
and placing on each table. Customers scan these QR codes to order without waiting for staff.

---

#### 3.4.1 Steps to Generate Table QR

1. Open **Cafe Management > Generate Table QR**
2. **Select card header** (what appears above the QR on each card):
   - **Text** - Cafe name (from Settings) printed as text
   - **Logo** - Cafe logo (from Cafe Profile) printed as image
3. **Select tables** - check the boxes for tables you want:
   - Shows: table label + ID
   - Count displayed: "(3/12 selected)"
   - You can select all or just specific tables
4. Tap **"Generate PDF"** button
5. Wait until PDF is ready
6. After generation:
   - Tap **"Share"** (link icon in top bar or button below) to:
     - Send to printer (Print)
     - Save to Files
     - Share via WhatsApp/email
   - Or tap **"Generate New PDF"** to regenerate with different selections

#### 3.4.2 QR Card Format

Each card contains:
```
+-------------------------+
|      [LOGO / NAME]      |  <- Header (logo or text)
|                         |
|      +-----------+      |
|      |  QR CODE  |      |
|      |           |      |
|      +-----------+      |
|                         |
|       Table 1 (T0001)  |  <- Label + Table ID
+-------------------------+
```

#### 3.4.3 Printing Tips

- **Paper:** Use regular A4 paper or sticker paper
- **Printer:** Any printer connected to the device (Bluetooth/WiFi/USB)
- **Laminate:** Recommended to laminate cards for durability
- **Placement:** Use acrylic stands or paste directly on tables
- **If tables added:** Regenerate PDF for new tables only (select only the new ones)

### 3.5 Payment Gateway Settings

> Only available in Cloud Mode

See Section [11. Payment Gateway](#11-payment-gateway).

---

## 4. Settings

**Access:** Menu (...) > Settings

### 4.1 Staff Permissions

| Toggle | Function |
|--------|----------|
| **Staff can send to kitchen** | Allow staff to send orders to kitchen |
| **Staff can take payment** | Allow staff to accept payments |

### 4.2 Default Language

| Dropdown | Who Uses It |
|----------|-------------|
| Admin language | Language of admin screen |
| Ordering language | Language of staff ordering screen |
| Customer language (Cloud) | Language of customer online menu |
| Printer language | Language on receipts/kitchen slips |

### 4.3 Customer Order (Cloud only)

| Field/Toggle | Function |
|-------------|----------|
| **Auto-print to kitchen** (Switch) | Customer orders go straight to kitchen, or hold first |
| **Hold before kitchen** (Chip: 10s/15s/30s/60s) | How long to hold before sending |
| **Today's Special** (text field) | Promotional text shown on customer menu today |

### 4.4 Reports

| Field | Function |
|-------|----------|
| **Business Day Start** (dropdown, hour) | When business day begins (e.g., 8 AM) |
| **Business Day End** (dropdown, hour) | When business day ends (e.g., 11 PM) |

### 4.5 Security

| Item | Function |
|------|----------|
| **PIN Lock** (Switch) | Lock admin screen with PIN |
| **Change PIN** (button) | Change existing PIN |
| **Unlink Device** (button) | Disconnect this device from the cafe |

### 4.6 Printing & Hardware

| Item | Destination |
|------|-------------|
| **Printers** | Manage printers (Bluetooth/Sunmi/USB/Network) |
| **Devices & Hardware** | Customer Display settings |
| **Cash Drawer** | Cash drawer settings screen |
| **Background Setup** | Keep-Alive settings (prevent Android from killing app) |
| **Show print status** (Switch) | Display print status on screen |

### 4.7 Alert Sound

| Item | Function |
|------|----------|
| **Current sound** (label + Choose button) | Select new order notification sound |
| **Volume** (Slider 0-100%) | Adjust alert volume |
| **Test** (button) | Preview the selected sound |

### 4.8 Screen

| Toggle | Function |
|--------|----------|
| **Fullscreen** (Switch) | Hide system bars (full kiosk mode) |

### 4.9 Ambient / Screensaver

| Item | Function |
|------|----------|
| **Enable** (Switch) | Activate auto screensaver |
| **Start After** (FilterChips) | Idle time before activation (1/2/3/5/10 min) |
| **Guest-visible screen** (Switch) | Show useful info for customers during ambient |

### 4.10 About

| Button | Function |
|--------|----------|
| **Open Source Licenses** | View open source software licenses |

---

## 5. Devices & Staff

**Access:** Menu (...) > Devices & Staff

### 5.1 Staff Invitation (QR Ordering Mode)

| Item | Function |
|------|----------|
| **QR Code** (display) | Show to new staff device to scan |
| **Share** (button) | Share invite link via WhatsApp/etc |
| **Regenerate** (button) | Create new invite code (cancels old one) |
| **LAN Address** | WiFi address for joining LAN |

### 5.2 Secondary Admin (Cloud only, Main Admin only)

| Item | Function |
|------|----------|
| **Add Secondary Admin** (button) | Generate QR invite for second admin |
| **Add Operator** (button) | Generate QR invite for operator (RAZStudio) |
| QR + Share + Regenerate | Same as Staff Invitation |

### 5.3 Owner Recovery Key (Cloud only)

| Button | Function |
|--------|----------|
| **Show Owner Recovery QR** | Display owner recovery QR key (30 seconds) |

> [!] **IMPORTANT:** Save a screenshot of this QR in a safe place. It is needed if
> you lose access to the main device.

### 5.4 Connected Devices

Each connected device is shown as a card:

| Action | Function |
|--------|----------|
| **Approve** | Approve new device |
| **Reject** | Reject new device |
| **Revoke** | Cancel access for existing device |
| **Force Check-Out** | Force check-out staff who forgot |
| **Rename** | Change device name |
| **Promote to Main** | Upgrade to Main Admin |

---

## 6. Payment

### 6.1 Normal Payment Flow

1. Tap a table with an active order
2. On the **Order Detail Sheet**, tap a pay button:
   - **Pay Cash** - Enter amount given, calculate change (numpad calculator)
   - **Pay QR** - Display QR for customer to scan (e-wallet)
   - **Pay [Gateway]** - Payment via gateway (Touch 'n Go, DuitNow, etc.)
3. Receipt is printed (if printer connected)
4. Table turns green again

### 6.2 Split Payment

1. Tap table > Order Detail Sheet
2. Tap **Split Payment**
3. Select items to be paid by the first customer
4. Choose payment method (Cash/QR/Gateway)
5. Repeat for next customer
6. Final balance is paid as normal

### 6.3 Send to Kitchen

- After an order is created, tap **"Send to Kitchen"**
- Kitchen slip auto-prints (if auto-print enabled)
- Status changes to SENT_TO_KITCHEN

### 6.4 Cancel Order

- On Order Detail Sheet, tap **Cancel Order**
- Enter cancellation reason
- Order is marked CANCELLED (cannot be paid anymore)

---

## 7. Reports

**Access:** Menu (...) > Reports

### 7.1 Period Selection

| Option (FilterChip) | Meaning |
|---------------------|---------|
| Today | Today only |
| This Week | This week |
| This Month | This month |
| Custom | Select start & end date (date picker) |

### 7.2 Report Cards

| Card | Contents |
|------|----------|
| **Summary** | Total orders, gross total, average order value |
| **Payment Split** | Breakdown of Cash vs QR vs Gateway |
| **Per-Table** | Revenue per table |
| **Best Sellers** | Most popular items (overall) |
| **Top per Category** | Top items in each category |
| **Cancelled Orders** | Summary of cancelled orders |
| **Cash Drawer Openings** | How often the drawer was opened |

### 7.3 How to Read Reports

- **Summary > Total Orders** - How many customers served in the period
- **Summary > Gross Total** - Total revenue before any deductions
- **Summary > Average** - Average spend per customer (gross / orders)
- **Payment Split** - Which payment method customers prefer (helps decide if you need more QR/gateway options)
- **Per-Table** - Which tables generate the most revenue (useful for layout decisions)
- **Best Sellers** - What to stock more of; what to promote
- **Cancelled Orders** - If cancellation rate >10%, investigate why

### 7.4 Export / Print Reports

1. Scroll to the bottom of the Reports screen
2. Tap **"Export PDF"** button
3. A PDF file is generated
4. The Android **Share sheet** opens automatically - choose:
   - **Print** - Send directly to a connected printer
   - **Save to Files** - Store locally
   - **WhatsApp/Email** - Send to yourself or partners

### 7.5 Bill History (Receipt Search)

**Access:** Reports screen > tap the **Receipt icon** in the top bar

1. A **search box** appears at the top
2. Type any of:
   - Bill number
   - Table name/ID
   - Item name
   - Payment method (e.g., "CASH", "QR")
3. Results appear as cards showing: order ID, table, total, payment method, time
4. **Tap a bill** to see full details (all items, notes, amounts)
5. In the detail dialog, tap **"Reprint"** to reprint that receipt

> [i] Search runs against the local database - fast even with years of history.

---

## 8. Cash Drawer

### 8.1 Cash Drawer Settings

**Access:** Settings > Cash Drawer

| Item | Function |
|------|----------|
| **Enable cash drawer** (Switch) | Enable/disable physical drawer kick |
| **Kick through** (radio list) | Select which printer opens the drawer |
| **Cash Drawer PIN** (Set/Change) | Set a PIN for manual drawer opening |

> [i] **Note:** Disabling the drawer does NOT stop cash records. Every cash sale, float,
> and cash-out is still recorded - only the physical kick is turned off.

### 8.2 Cash Drawer Screen

**Access:** Menu (...) > Drawer

| Item | Function |
|------|----------|
| **Expected Balance** (RM display) | Expected cash amount in the drawer |
| **Opening Float** (numpad + Save) | Enter starting cash for the day |
| **Cash Out** (button) | Remove cash > Numpad + PIN + "Take out" |
| **Audit Trail** | List of all drawer events (open, close, sale, cash out) |

---

## 9. Backup & Restore

**Access:** Menu (...) > Backup

### 9.1 Export

| Button | Function |
|--------|----------|
| **Export** | Generate backup file (.json) |
| **Share** | Share backup file via WhatsApp/email/etc |

### 9.2 Import

| Step | Description |
|------|-------------|
| 1. "Select Backup File" | Pick .json file from storage |
| 2. Preview | See version, date, item counts |
| 3. "Restore" | Restore data (existing data will be erased!) |

### 9.3 Google Drive (Cloud only)

> [!] **TESTING MODE:** Google Drive integration is still in testing. Only registered
> tester accounts can use this feature.

| Button | Function |
|--------|----------|
| **Save to Google Drive** | Save cafe bundle to Drive |
| **Remove** | Delete bundle from Drive (requires confirmation) |

---

## 10. Payment Monitor

> [!] **ALPHA:** Payment Monitor / Notification Listener is still in alpha phase.
> This feature is being tested and may not work fully on all devices. Do not rely
> solely on it for payment verification.

**Access:** Menu (...) > Payment Monitor

Monitors e-wallet notifications and automatically matches incoming payments with active orders.

### 10.1 Permission Status

| Item | Function |
|------|----------|
| Notification Access (status) | Must be granted for the listener to work |
| "Open Notification Settings" | Opens Android system settings |
| Battery Optimization (status) | Should be disabled |
| "Disable Battery Optimization" | Request bypass from Android |

### 10.2 Schedule

Listener is active only during business hours (follows Business Day Start/End from Settings).

### 10.3 Listener Settings

| Item | Function |
|------|----------|
| **Enable Payment Listener** (Switch) | Turn listener on/off |
| **Monitored Apps** (Checkboxes) | Select e-wallets to monitor |
| - Touch 'n Go | Check/uncheck |
| - GrabPay | Check/uncheck |
| - Boost | Check/uncheck |
| - ShopeePay | Check/uncheck |
| - MAE by Maybank | Check/uncheck |
| - CIMB Clicks | Check/uncheck |
| **Auto-start on boot** (Switch) | Start automatically when device restarts |

### 10.4 Alert Settings

| Toggle | Function |
|--------|----------|
| **Sound** (Switch) | Play sound when payment captured |
| **Vibration** (Switch) | Vibrate when payment captured |
| **Toast Notification** (Switch) | Show brief popup |

### 10.5 Recent Payments

List of captured payments - each card shows:
- Amount (RM)
- Wallet app
- Sender (if available)
- Timestamp
- Match status: **MATCHED** (green), **AMBIGUOUS** (yellow), **UNMATCHED** (grey)

---

## 11. Payment Gateway

**Access:** Cafe Management > Payment Gateway Settings

> Only available in Cloud Mode

### 11.1 Select Provider

Scrollable FilterChip bar - tap the provider you use (e.g., Touch 'n Go, DuitNow).

### 11.2 Credential Fields

| Field | Description |
|-------|-------------|
| Depends on provider | Enter Merchant ID, Secret Key, etc. |
| Secret fields (masked) | Not displayed after saving |
| "already set" placeholder | Means key is already stored on server |

### 11.3 Toggles

| Toggle | Function |
|--------|----------|
| **Sandbox mode** (Switch) | Test mode (OFF = production, requires confirmation) |
| **Enabled** (Switch) | Enable/disable this provider |

### 11.4 Payment Channels

Each payment method has its own Switch:
- Toggle ON/OFF depending on which methods your cafe accepts

### 11.5 Save

Tap **Save** after completing configuration.

---

## 12. Operating Modes

### 12.1 Cloud Mode (QR Ordering)

- Requires internet
- Customers can scan table QR to order themselves
- Multiple devices can connect
- All data in cloud (Supabase)
- Supports Payment Gateway

### 12.2 LAN Mode (Wireless AP)

- No internet needed - local WiFi only
- One device acts as Host, others Join
- Data stored on Host device
- **Host this cafe** > Show pairing QR
- **Join this cafe** > Scan QR to join

### 12.3 Kiosk Mode

- Single device only
- No tables - orders numbered (running number)
- Suitable for counters/food trucks
- Pay immediately after ordering

### Switching Modes

1. Main screen > **Setup Wizard** (footer on RoleSelectScreen)
2. Select new mode
3. Fill in required information
4. Tap **Save**

> [!] Switching modes may affect existing data. Create a backup first.

---

## 13. Troubleshooting

### App crashes / Force close
- Make sure all permissions are granted
- Ensure sufficient storage space
- Try: Settings > Apps > Warung Tom Yam > Clear Cache

### Printer not working
- Check Bluetooth/USB connection
- Make sure printer is selected in Settings > Printers
- Try "Test Print" on the Printers screen

### Orders not reaching kitchen
- Check kitchen printer is connected
- Check "Auto-print to kitchen" setting (Settings > Customer Order)
- Make sure category slip route is correct (Menu Management > Edit Category)

### Staff cannot check-in
- Check staff device GPS is turned on
- Check radius in Cafe Profile is sufficient
- Make sure "Capture Location" was done on the admin device

### Cash drawer won't open
- Check "Enable cash drawer" (Switch) in Cash Drawer Settings
- Check that the connected printer supports cash drawer
- Make sure printer is selected in "Kick through"

### Payment Monitor not capturing payments
- Open Settings > Notification Access > allow this app
- Disable Battery Optimization for this app
- Make sure the correct e-wallet apps are checked in Monitored Apps
- Make sure current time is within Business Hours

### Split payment fails
- This usually happens if "Auto-print to kitchen" is turned off
- Orders must be in SENT_TO_KITCHEN status before they can be paid
- Make sure the order was sent to kitchen before attempting split

### Lost admin access
- Use **Owner Recovery QR** (if you have a screenshot)
- Scan that QR on a new device
- Or contact RAZStudio support

---

## Important Notes

1. **Save Owner Recovery QR** - this is the only way to recover access if the device is lost
2. **Regular backups** - Export backup at least weekly
3. **PIN Lock** - Enable to prevent unauthorized access to Settings
4. **Cash Drawer PIN** - Set to prevent staff from opening the drawer arbitrarily
5. **Stable WiFi** - For Cloud Mode, ensure a stable internet connection at all times
6. **Keep app updated** - Always use the latest version for fixes and new features

## Feature Status

| Feature | Status | Note |
|---------|--------|------|
| Google Sign-In | [TEST] Testing | Registered tester accounts only |
| Google Drive Backup | [TEST] Testing | Registered tester accounts only |
| Payment Monitor / Notification Listener | [ALPHA] Alpha | Early development, may be unstable |
| Payment Listener Cloud Sync (FullQR) | [TEST] Testing | See Testing Phase below |
| All other features | [OK] Production | Stable and ready for use |

## Testing Phase: Payment Listener Cloud Sync (FullQR Mode)

The Payment Notification Listener now supports **cloud forwarding** in FullQR Cloud mode.
This allows a Secondary Admin device to capture e-wallet notifications and forward them to
the Main Admin device for auto-matching against open orders.

### How It Works (End-to-End Pipeline)

```
Secondary Admin (e.g., Infinix)
  1. Runs the Notification Listener
  2. Captures bank/e-wallet payment notification
  3. Inserts into local Room DB
  4. Immediately calls broadcastCloud()
  5. POSTs JSON to /api/payment-alerts (Supabase Edge Function)

Supabase Edge Function
  - Accepts any Admin token (ADMIN or ADMIN_SECONDARY)
  - Inserts capture into payment_alerts table with source_device_id
  - Enforces: only role = ADMIN can drain (GET) alerts

Main Admin (e.g., D3 Mini POS terminal)
  - Poll loop in RealtimeService calls pollCapturedPayments()
  - Fetches new rows since lastSeenPaymentTimestamp
  - For each row, invokes paymentAlertHandler.handlePaymentAlert(...)
  - Triggers toast, sound, vibration (governed by Payment Monitor toggles)
  - Auto-matches payment amount against open orders
```

### Test Plan

1. Re-pair the Secondary Admin device (e.g., Infinix) via Devices & Staff
2. Enable **Notification Listener** in its overflow menu
3. Trigger a real payment notification (e.g., Touch 'n Go, GrabPay)
4. Within ~10 seconds, the Main Admin device should:
   - Poll and pick up the forwarded alert
   - Auto-match it to an open order
   - Show toast/sound/vibration (if enabled in Payment Monitor settings)

### Diagnostics (If It Fails)

**On Main Admin (D3 Mini) - run:**
```
adb logcat -s "PaymentAlertPoll" "RealtimeService"
```
Look for: `Forwarded payment auto-matched` or `Payment alert poll`

**On Secondary Admin (Infinix) - run:**
```
adb logcat -s "PaymentBroadcast" "NotificationListener"
```
Look for: `Forwarded payment alert` or `broadcastCloud success`

### Requirements for This Feature

- Operating Mode: **Cloud (FullQR)**
- Main Admin device: running and logged in
- Secondary Admin device: paired, approved, Notification Listener enabled
- Both devices: internet connected
- Supabase migration `payment_alerts` table must be applied

---

*This document was generated based on the Warung Tom Yam POS app source code v1.0.*
*For technical support, contact RAZStudio.*
