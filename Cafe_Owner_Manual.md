# Warung Tom Yam POS — Panduan Pemilik Kafe / Café Owner Manual

## Kandungan / Table of Contents

1. [Mula Guna / Getting Started](#1-mula-guna--getting-started)
2. [Skrin Utama — Table View](#2-skrin-utama--table-view)
3. [Pengurusan Kafe / Café Management](#3-pengurusan-kafe--café-management)
4. [Tetapan / Settings](#4-tetapan--settings)
5. [Peranti & Kakitangan / Devices & Staff](#5-peranti--kakitangan--devices--staff)
6. [Pembayaran / Payment](#6-pembayaran--payment)
7. [Laporan / Reports](#7-laporan--reports)
8. [Laci Tunai / Cash Drawer](#8-laci-tunai--cash-drawer)
9. [Backup & Restore](#9-backup--restore)
10. [Payment Monitor (Pemantau Pembayaran)](#10-payment-monitor)
11. [Payment Gateway (Gerbang Pembayaran)](#11-payment-gateway)
12. [Mode Operasi / Operating Modes](#12-mode-operasi--operating-modes)
13. [Penyelesaian Masalah / Troubleshooting](#13-penyelesaian-masalah--troubleshooting)

---

## 1. Mula Guna / Getting Started

### Pemasangan Pertama / First-Time Setup

1. **Buka aplikasi** — skrin kebenaran (permission) akan muncul.
   - Benarkan **Location**, **Nearby Devices**, dan **Notifications**.
   - Tekan "Allow" untuk setiap kebenaran.

2. **Google Sign-In** (Cloud Mode sahaja)
   - Tekan "Sign in with Google" dan pilih akaun.
   - Jika anda sudah ada kafe, pilih dari senarai.
   - Jika kafe baru, tekan "Set up this café."

   > ⚠️ **TESTING MODE:** Google Sign-In dan Google Drive masih dalam fasa ujian. Fungsi ini mungkin tidak stabil dan tertakluk kepada perubahan. Hanya akaun yang didaftarkan sebagai tester boleh menggunakannya buat masa ini.

3. **Pilih Mode** pada skrin Role Select:
   - **QR Ordering Mode** — untuk kafe yang guna pesanan QR pelanggan
   - **Wireless AP Mode** — LAN tanpa internet
   - **Kiosk Mode** — kaunter tanpa meja

4. **Setup Wizard** (jika kafe baru)
   - Pilih Operating Mode (Cloud / LAN / Kiosk)
   - Masukkan maklumat kafe (Nama Kafe, URL, dsb.)
   - Tekan **Save**

### Bahasa / Language

Tekan ikon **🌐 (Globe)** di penjuru atas-kanan pada mana-mana skrin untuk tukar bahasa:
- BM (Bahasa Melayu)
- EN (English)
- 中文 (Chinese)
- தமிழ் (Tamil)
- ไทย (Thai)

### Tema / Theme

Tekan ikon **🎨 (Palette)** untuk pilih tema rupa:
- Bold, Edgy, Elegant, Luxury, Minimalist, Soft

---

## 2. Skrin Utama — Table View

Selepas login, anda akan nampak **grid meja** berwarna mengikut status:

| Warna | Maksud |
|-------|--------|
| Hijau (Green) | MEJA KOSONG — sedia untuk pelanggan |
| Merah (Red) | SEDANG DIPESAN — ada pesanan aktif |
| Biru (Blue) | HANTAR KE DAPUR — pesanan sedang disediakan |
| Kuning (Yellow) | SEDIA — makanan sedia untuk diantar |

### Tindakan pada Meja

- **Tap meja kosong** → Buka lembaran buat pesanan baru
- **Tap meja berisi** → Buka butiran pesanan (bayar, tambah, hantar ke dapur)

### Butang-Butang Utama

| Butang | Fungsi |
|--------|--------|
| **＋** (FAB kanan bawah) | Buat pesanan dine-in baru |
| **🧮** (FAB kiri bawah) | Kalkulator pantas |
| **⋮** (Overflow menu) | Buka menu navigasi penuh |

### Menu Overflow (⋮)

> ⚠️ Memerlukan **PIN Lock** jika diaktifkan.

| Item | Fungsi |
|------|--------|
| Pending Kitchen Prints | Lihat cetakan yang belum dihantar |
| Recent Prints | Sejarah cetakan terkini |
| Café Management | Urus profil, menu, meja, QR |
| Devices & Staff | Urus peranti & jemputan kakitangan |
| Payment Monitor | Pemantau notifikasi e-wallet |
| Notification Listener | Toggle on/off (Switch) |
| Drawer | Buka skrin laci tunai |
| Reports | Laporan jualan |
| Backup | Export/Import data |
| Settings | Semua tetapan |
| Sign Out | Tutup sesi |
| Sign Out with Closing | Tutup sesi + laporan penutupan |

---

## 3. Pengurusan Kafe / Café Management

Akses: **Menu ⋮ → Café Management**

### 3.1 Profil Kafe / Café Profile

| Field/Button | Keterangan |
|-------------|------------|
| **Nama Kafe** (text field) | Nama yang dipaparkan di resit & website |
| **Logo** (Pick Logo / Change Logo) | Muat naik logo kafe (format gambar) |
| **Capture Location** (button) | Ambil koordinat GPS lokasi kafe |
| **Radius** (number field) | Jarak maksimum staff boleh check-in (meter) |
| **Timezone** | Zon masa dipaparkan (auto-detect) |
| **Payment QR** (Upload/Replace/Remove) | QR kod pembayaran e-wallet untuk dipaparkan |
| **Menu Preset** (Load Preset) | Muatkan template menu siap pakai |
| **Cancel / Save** (bottom bar) | Batal atau simpan perubahan |

### 3.2 Pengurusan Menu / Menu Management

**Akses:** Café Management → Menu Management

---

#### 3.2.1 Membuat Kategori Baru / Creating a New Category

1. Tekan ikon **📁 (New Folder)** di top bar
2. Masukkan nama kategori (cth: "NASI", "MINUMAN", "UDANG/SOTONG")
3. Tekan **OK**
4. Kategori baru muncul sebagai tab baru

#### 3.2.2 Menetapkan Printer Route untuk Kategori / Setting Kitchen Slip Route

Setiap kategori mempunyai **Kitchen Slip Route** yang menentukan pencetak mana yang mencetak item kategori tersebut. Ini membolehkan anda menghalakan pesanan makanan ke dapur dan minuman ke bar.

1. **Long-press (tekan lama)** pada tab kategori
2. Pilih **Edit** dari menu popup
3. Dalam dialog Category Editor:
   - Masukkan nama paparan dalam pelbagai bahasa:
     - English
     - Bahasa Melayu
     - 中文 (Chinese)
     - தமிழ் (Tamil)
     - ไทย (Thai)
   - Pilih **Kitchen Slip Route**:
     - **Food** — Item dalam kategori ini dicetak pada slip dapur MAKANAN
     - **Beverage** — Item dalam kategori ini dicetak pada slip dapur MINUMAN
4. Tekan **Save**

> 💡 **Contoh Setup:**
> - Kategori "NASI", "LAUK", "GORENG" → Route: **Food** → dicetak di pencetak dapur
> - Kategori "MINUMAN", "AIS", "PANAS" → Route: **Beverage** → dicetak di pencetak bar

> 💡 **Nota:** Jika anda hanya ada satu pencetak dapur, kedua-dua route (Food & Beverage) akan dicetak di pencetak yang sama. Route hanya bermakna jika anda ada dua pencetak berasingan.

#### 3.2.3 Mengubah Susunan Kategori / Reordering Categories

1. Tekan ikon **⇅ (Reorder)** di top bar
2. Gunakan butang atas/bawah untuk susun semula kategori
3. Susunan ini menentukan urutan tab pada skrin pesanan

#### 3.2.4 Menyusun Item dalam Kategori / Sorting Items

1. Tekan ikon **Sort** di top bar
2. Pilih **Auto-sort** (susun automatik A-Z) atau susun manual dengan butang atas/bawah

#### 3.2.5 Padam Kategori / Deleting a Category

1. **Long-press (tekan lama)** pada tab kategori
2. Pilih **Delete**
3. ⚠️ Semua item dalam kategori tersebut akan tiada kategori

---

#### 3.2.6 Menambah Item Menu Baru / Adding a New Menu Item

1. Pilih tab kategori yang dikehendaki
2. Tekan **＋** (FAB) atau ikon Add di top bar
3. Isi borang item:

**Langkah 1 — Pilih Kategori:**
- Pilih kategori dari senarai
- Atau tekan "New category" untuk buat kategori baru terus

**Langkah 2 — Isi Maklumat Item:**

| Field | Keterangan | Wajib? |
|-------|------------|--------|
| **Category** | Kategori utama item (sudah dipilih) | ✅ |
| **Also show in** (FilterChips) | Papar item ini dalam kategori tambahan juga. Cth: "Nasi Goreng Udang" dalam NASI dan UDANG | Tidak |
| **Code** | Kod ringkas (untuk carian pantas) | Tidak |
| **Name BM** | Nama dalam Bahasa Melayu | ✅ |
| **+ Bahasa Lain** (expandable) | Tambah nama dalam EN, 中文, Tamil, Thai | Tidak |

**Pricing Mode (Mod Harga):**

| Mode | Bilakah Guna | Apa yang Diisi |
|------|-------------|----------------|
| **Single Price** (Harga Tunggal) | Item dengan satu saiz/harga sahaja | Masukkan harga (cth: RM 8.00) |
| **Multiple Price** (Pelbagai Harga) | Item dengan saiz S/M/L | Masukkan 3 harga: Small, Medium, Large |
| **Market Price** (Harga Pasaran) | Harga berubah setiap hari (cth: ikan, udang) | Tiada harga diisi — ditanya setiap hari |

**Contoh Multiple Price:**
```
Small (S):  RM 3.00   ← Teh Ais kecil
Medium (M): RM 4.50   ← Teh Ais sederhana
Large (L):  RM 6.00   ← Teh Ais besar
```

**Toggles Tambahan:**

| Toggle | Fungsi |
|--------|--------|
| **Do not translate** (Switch) | Nama item tidak akan diterjemah ke bahasa lain (cth: nama unik/brand) |
| **Ask me daily** (Switch) | Popup akan tanya setiap hari sama ada item ini tersedia hari ini |

**Gambar Item:**
- Tekan butang gambar untuk muat naik foto item
- Gambar ini dipapar pada menu digital pelanggan (QR ordering)

4. Tekan **Save**

---

#### 3.2.7 Mengedit Item Menu / Editing a Menu Item

1. Pada senarai item, **swipe kiri** pada item
2. Tekan ikon **Edit** (pensel)
3. Ubah mana-mana field yang perlu
4. Tekan **Save**

#### 3.2.8 Memadam Item Menu / Deleting a Menu Item

1. **Swipe kiri** pada item
2. Tekan ikon **Delete** (sampah merah)
3. Sahkan pemadaman dalam dialog

#### 3.2.9 Toggle Ketersediaan Item / Toggle Item Availability

- Setiap item mempunyai **Switch** di sebelah kanan
- **ON** = tersedia hari ini (dipapar pada menu)
- **OFF** = tidak tersedia (tersembunyi dari pelanggan, kelabu pada admin)

> 💡 Berguna untuk item yang habis stok hari ini — matikan tanpa perlu padam.

---

#### 3.2.10 Contoh Setup Menu Warung / Example Warung Menu Setup

```
📂 NASI (Route: Food)
   ├── Nasi Putih          RM 2.00  (Single Price)
   ├── Nasi Goreng         RM 8.00  (Single Price)
   └── Nasi Lemak          RM 6.00 / 8.00 / 10.00 (Multiple: S/M/L)

📂 LAUK (Route: Food)
   ├── Ayam Goreng         RM 5.00  (Single Price)
   ├── Ikan Bakar          Market Price (ditanya setiap hari)
   └── Udang Masak Lemak   Market Price

📂 MINUMAN (Route: Beverage)
   ├── Teh Ais             RM 3.00 / 4.50 / 6.00 (S/M/L)
   ├── Kopi O              RM 2.50  (Single Price)
   └── Air Suam            RM 0.00  (Single Price, percuma)
```

### 3.3 Pengurusan Meja / Tables Management

> Hanya ada dalam Table Service mode (tiada dalam Kiosk)

**Akses:** Café Management → Tables Management

---

#### 3.3.1 Menambah Meja Baru / Adding a New Table

1. Dalam field **Label**, masukkan nama/label meja (cth: "Meja 1", "VIP", "Luar")
2. Tekan **Add**
3. Meja ditambah dengan ID automatik (T0001, T0002, …)

> 💡 ID meja dijana automatik dan tidak boleh ditukar. Label adalah nama yang dipaparkan.

**Had:** Maksimum meja bergantung pada pelan (lihat kaunter meja semasa di skrin).

#### 3.3.2 Menambah Slot Take-Out (Tapaw)

1. Di bahagian **Take-out**, tekan **Add Take-out**
2. Slot take-out dipapar berasingan dari meja dine-in
3. Gunakan untuk pelanggan yang bawa pulang — tidak perlu QR meja

#### 3.3.3 Mengedit Label Meja / Renaming a Table

1. Tekan ikon **✏️ (Edit/Pensel)** di sebelah kanan meja
2. Field label bertukar boleh diedit (inline)
3. Ubah nama, kemudian tekan **Save**
4. Atau tekan **Cancel** untuk batal

#### 3.3.4 Memadam Meja / Deleting a Table

1. Tekan ikon **🗑️ (Sampah merah)** di sebelah kanan meja
2. Meja dipadam serta-merta

> ⚠️ **Amaran:** Jangan padam meja yang masih ada pesanan aktif. Selesaikan pesanan terlebih dahulu.

#### 3.3.5 Maklumat Dipapar per Meja

| Info | Keterangan |
|------|------------|
| **Label** | Nama meja (boleh diedit) |
| **ID** | Kod unik automatik (T0001, dsb.) — tidak boleh diubah |

#### 3.3.6 Contoh Setup Meja Warung

```
Dine-In (12 meja):
   T0001 — "Meja 1"
   T0002 — "Meja 2"
   ...
   T0010 — "Meja 10"
   T0011 — "VIP 1"
   T0012 — "VIP 2"

Take-Out (3 slot):
   TW001 — "Tapaw 1"
   TW002 — "Tapaw 2"
   TW003 — "Tapaw 3"
```

### 3.4 Generate Table QR

> Hanya ada jika web ordering aktif

Menjana kad QR meja dalam format PDF untuk dicetak dan diletak di setiap meja.

### 3.5 Payment Gateway Settings

> Hanya ada dalam Cloud Mode

Lihat Bahagian [11. Payment Gateway](#11-payment-gateway).

---

## 4. Tetapan / Settings

**Akses:** Menu ⋮ → Settings

### 4.1 Staff Permissions

| Toggle | Fungsi |
|--------|--------|
| **Staff can send to kitchen** | Benarkan staff hantar pesanan ke dapur |
| **Staff can take payment** | Benarkan staff terima pembayaran |

### 4.2 Default Language

| Dropdown | Siapa guna |
|----------|-----------|
| Admin language | Bahasa skrin admin |
| Ordering language | Bahasa skrin staff |
| Customer language (Cloud) | Bahasa menu pelanggan online |
| Printer language | Bahasa pada resit/slip dapur |

### 4.3 Customer Order (Cloud sahaja)

| Field/Toggle | Fungsi |
|-------------|--------|
| **Auto-print to kitchen** (Switch) | Pesanan pelanggan terus ke dapur, atau tahan dulu |
| **Hold before kitchen** (Chip: 10s/15s/30s/60s) | Berapa lama tahan sebelum hantar |
| **Today's Special** (text field) | Teks promosi hari ini pada menu pelanggan |

### 4.4 Reports

| Field | Fungsi |
|-------|--------|
| **Business Day Start** (dropdown, jam) | Bila hari perniagaan mula (cth: 8 AM) |
| **Business Day End** (dropdown, jam) | Bila hari perniagaan tamat (cth: 11 PM) |

### 4.5 Security

| Item | Fungsi |
|------|--------|
| **PIN Lock** (Switch) | Kunci skrin admin dengan PIN |
| **Change PIN** (button) | Tukar PIN sedia ada |
| **Unlink Device** (button) | Putuskan peranti dari kafe ini |

### 4.6 Printing & Hardware

| Item | Destinasi |
|------|-----------|
| **Printers** | Urus pencetak (Bluetooth/Sunmi/USB/Network) |
| **Devices & Hardware** | Paparan pelanggan (Customer Display) |
| **Cash Drawer** | Tetapan laci tunai → Skrin Cash Drawer Settings |
| **Background Setup** | Tetapan Keep-Alive (cegah Android bunuh app) |
| **Show print status** (Switch) | Papar status cetakan pada skrin |

### 4.7 Alert Sound

| Item | Fungsi |
|------|--------|
| **Current sound** (label + Choose button) | Pilih bunyi notifikasi pesanan baru |
| **Volume** (Slider 0–100%) | Laraskan kelantangan bunyi |
| **Test** (button) | Cuba dengar bunyi pilihan |

### 4.8 Screen

| Toggle | Fungsi |
|--------|--------|
| **Fullscreen** (Switch) | Sorok system bar (mod kiosk penuh) |

### 4.9 Ambient / Screensaver

| Item | Fungsi |
|------|--------|
| **Enable** (Switch) | Aktifkan screensaver auto |
| **Start After** (FilterChips) | Masa idle sebelum aktif (1/2/3/5/10 min) |
| **Guest-visible screen** (Switch) | Papar info berguna untuk pelanggan semasa ambient |

### 4.10 About

| Button | Fungsi |
|--------|--------|
| **Open Source Licenses** | Lihat lesen perisian sumber terbuka |

---

## 5. Peranti & Kakitangan / Devices & Staff

**Akses:** Menu ⋮ → Devices & Staff

### 5.1 Staff Invitation (QR Ordering Mode)

| Item | Fungsi |
|------|--------|
| **QR Code** (paparan) | Tunjukkan pada peranti staff baru untuk scan |
| **Share** (button) | Kongsi pautan jemputan melalui WhatsApp/dll |
| **Regenerate** (button) | Buat kod jemputan baru (batal yang lama) |
| **LAN Address** | Alamat WiFi untuk join LAN |

### 5.2 Secondary Admin (Cloud sahaja, Main Admin sahaja)

| Item | Fungsi |
|------|--------|
| **Add Secondary Admin** (button) | Jana QR jemputan admin kedua |
| **Add Operator** (button) | Jana QR jemputan operator (RAZStudio) |
| QR + Share + Regenerate | Sama seperti Staff Invitation |

### 5.3 Owner Recovery Key (Cloud sahaja)

| Button | Fungsi |
|--------|--------|
| **Show Owner Recovery QR** | Papar QR kunci pemulihan pemilik (30 saat) |

> ⚠️ **PENTING:** Simpan screenshot QR ini di tempat selamat. Ia diperlukan jika anda hilang akses kepada peranti utama.

### 5.4 Connected Devices

Setiap peranti disambungkan dipaparkan sebagai kad:

| Tindakan | Fungsi |
|----------|--------|
| **Approve** | Luluskan peranti baru |
| **Reject** | Tolak peranti baru |
| **Revoke** | Batalkan akses peranti sedia ada |
| **Force Check-Out** | Paksa check-out staff yang lupa |
| **Rename** | Tukar nama peranti |
| **Promote to Main** | Naikkan taraf ke Admin Utama |

---

## 6. Pembayaran / Payment

### 6.1 Aliran Pembayaran Biasa

1. Tap meja yang berisi pesanan
2. Pada **Order Detail Sheet**, tekan butang bayar:
   - **Pay Cash** — Masukkan jumlah diberi, kira baki (numpad kalkulator)
   - **Pay QR** — Paparkan QR untuk pelanggan scan (e-wallet)
   - **Pay [Gateway]** — Pembayaran melalui gateway (Touch 'n Go, DuitNow, dll)
3. Resit dicetak (jika pencetak disambungkan)
4. Meja bertukar hijau semula

### 6.2 Split Payment (Bayar Berasingan)

1. Tap meja → Order Detail Sheet
2. Tekan **Split Payment**
3. Pilih item yang dibayar oleh pelanggan pertama
4. Pilih kaedah bayar (Cash/QR/Gateway)
5. Ulangi untuk pelanggan seterusnya
6. Baki terakhir dibayar seperti biasa

### 6.3 Hantar ke Dapur / Send to Kitchen

- Selepas pesanan dibuat, tekan **"Send to Kitchen"**
- Slip dapur dicetak automatik (jika auto-print diaktifkan)
- Status bertukar ke SENT_TO_KITCHEN

### 6.4 Batal Pesanan / Cancel Order

- Pada Order Detail Sheet, tekan **Cancel Order**
- Masukkan sebab pembatalan
- Pesanan ditandakan CANCELLED (tidak boleh dibayar lagi)

---

## 7. Laporan / Reports

**Akses:** Menu ⋮ → Reports

### 7.1 Tempoh / Period

| Pilihan (FilterChip) | Maksud |
|-----------------------|--------|
| Today | Hari ini |
| This Week | Minggu ini |
| This Month | Bulan ini |
| Custom | Pilih tarikh mula & akhir (date picker) |

### 7.2 Kad Laporan

| Kad | Kandungan |
|-----|-----------|
| **Summary** | Jumlah pesanan, jumlah kasar, purata pesanan |
| **Payment Split** | Pecahan Cash vs QR vs Gateway |
| **Per-Table** | Jumlah per meja |
| **Best Sellers** | Item paling laris (keseluruhan) |
| **Top per Category** | Item teratas setiap kategori |
| **Cancelled Orders** | Ringkasan pesanan dibatalkan |
| **Cash Drawer Openings** | Kekerapan buka laci |

### 7.3 Tindakan

| Button | Fungsi |
|--------|--------|
| **Export PDF** | Muat turun laporan sebagai PDF |
| **Bill History** (ikon top bar) | Lihat sejarah bil individu |

---

## 8. Laci Tunai / Cash Drawer

### 8.1 Tetapan Laci / Cash Drawer Settings

**Akses:** Settings → Cash Drawer

| Item | Fungsi |
|------|--------|
| **Enable cash drawer** (Switch) | Aktifkan/nyahaktifkan tendangan fizikal laci |
| **Kick through** (senarai radio) | Pilih pencetak mana yang buka laci |
| **Cash Drawer PIN** (Set/Change) | Tetapkan PIN untuk buka laci manual |

> 💡 **Nota:** Mematikan laci TIDAK menghentikan rekod tunai. Setiap jualan tunai, float, dan cash-out tetap dicatat — hanya tendangan fizikal yang ditamatkan.

### 8.2 Skrin Laci Tunai / Drawer Screen

**Akses:** Menu ⋮ → Drawer

| Item | Fungsi |
|------|--------|
| **Expected Balance** (paparan RM) | Jumlah tunai dijangka dalam laci |
| **Opening Float** (numpad + Save) | Masukkan tunai permulaan hari |
| **Cash Out** (button) | Keluarkan tunai → Numpad + PIN + "Take out" |
| **Audit Trail** | Senarai semua event laci (buka, tutup, jualan, cash out) |

---

## 9. Backup & Restore

**Akses:** Menu ⋮ → Backup

### 9.1 Export

| Button | Fungsi |
|--------|--------|
| **Export** | Jana fail backup (.json) |
| **Share** | Kongsi fail backup melalui WhatsApp/email/dll |

### 9.2 Import

| Step | Keterangan |
|------|------------|
| 1. "Select Backup File" | Pilih fail .json dari storan |
| 2. Preview | Lihat versi, tarikh, bilangan item |
| 3. "Restore" | Pulihkan data (⚠️ data sedia ada akan dipadam) |

### 9.3 Google Drive (Cloud sahaja)

> ⚠️ **TESTING MODE:** Integrasi Google Drive masih dalam fasa ujian. Hanya akaun tester berdaftar boleh menggunakan fungsi ini. Fungsi mungkin berubah atau tidak tersedia pada masa hadapan.

| Button | Fungsi |
|--------|--------|
| **Save to Google Drive** | Simpan bundle kafe ke Drive |
| **Remove** | Padam bundle dari Drive (perlu pengesahan) |

---

## 10. Payment Monitor

> ⚠️ **ALPHA:** Payment Monitor / Notification Listener masih dalam fasa alpha. Fungsi ini sedang diuji dan mungkin tidak berfungsi sepenuhnya pada semua peranti. Gunakan dengan berhati-hati dan jangan bergantung sepenuhnya kepadanya untuk pengesahan pembayaran.

**Akses:** Menu ⋮ → Payment Monitor

Pemantau notifikasi e-wallet yang secara automatik padankan pembayaran masuk dengan pesanan aktif.

### 10.1 Permission Status

| Item | Fungsi |
|------|--------|
| Notification Access (status) | Mesti dibenarkan untuk berfungsi |
| "Open Notification Settings" | Buka tetapan sistem Android |
| Battery Optimization (status) | Harus dinyahaktifkan |
| "Disable Battery Optimization" | Minta bypass dari Android |

### 10.2 Schedule

Listener aktif hanya semasa waktu perniagaan (ikut Business Day Start/End dari Settings).

### 10.3 Listener Settings

| Item | Fungsi |
|------|--------|
| **Enable Payment Listener** (Switch) | Aktifkan/matikan listener |
| **Monitored Apps** (Checkboxes) | Pilih e-wallet yang dipantau |
| — Touch 'n Go | ✓/✗ |
| — GrabPay | ✓/✗ |
| — Boost | ✓/✗ |
| — ShopeePay | ✓/✗ |
| — MAE by Maybank | ✓/✗ |
| — CIMB Clicks | ✓/✗ |
| **Auto-start on boot** (Switch) | Mula sendiri bila peranti restart |

### 10.4 Alert Settings

| Toggle | Fungsi |
|--------|--------|
| **Sound** (Switch) | Bunyi bila bayaran ditangkap |
| **Vibration** (Switch) | Getar bila bayaran ditangkap |
| **Toast Notification** (Switch) | Papar popup ringkas |

### 10.5 Recent Payments

Senarai pembayaran yang ditangkap — setiap kad menunjukkan:
- Jumlah (RM)
- Aplikasi wallet
- Penghantar (jika ada)
- Masa
- Status padanan: **MATCHED** (hijau), **AMBIGUOUS** (kuning), **UNMATCHED** (kelabu)

---

## 11. Payment Gateway

**Akses:** Café Management → Payment Gateway Settings

> Hanya tersedia dalam Cloud Mode

### 11.1 Pilih Provider

Bar scroll FilterChips — tap provider yang digunakan (cth: Touch 'n Go, DuitNow).

### 11.2 Credential Fields

| Field | Keterangan |
|-------|------------|
| Bergantung pada provider | Masukkan Merchant ID, Secret Key, dll |
| Medan rahsia (●●●) | Tidak dipaparkan selepas disimpan |
| Placeholder "already set" | Bermaksud kunci sudah tersimpan di server |

### 11.3 Toggles

| Toggle | Fungsi |
|--------|--------|
| **Sandbox mode** (Switch) | Mod ujian (OFF = production, perlu pengesahan) |
| **Enabled** (Switch) | Aktifkan/matikan provider ini |

### 11.4 Payment Channels

Setiap kaedah bayar mempunyai Switch sendiri:
- Toggle ON/OFF mengikut kaedah yang diterima di kafe anda

### 11.5 Simpan

Tekan **Save** selepas selesai konfigurasi.

---

## 12. Mode Operasi / Operating Modes

### 12.1 Cloud Mode (QR Ordering)

- Memerlukan internet
- Pelanggan boleh scan QR meja untuk pesan sendiri
- Pelbagai peranti boleh disambungkan
- Semua data di cloud (Supabase)
- Menyokong Payment Gateway

### 12.2 LAN Mode (Wireless AP)

- Tanpa internet — WiFi tempatan sahaja
- Satu peranti jadi Host, yang lain Join
- Data tersimpan di peranti Host
- **Host this café** → Paparkan QR pairing
- **Join this café** → Scan QR untuk join

### 12.3 Kiosk Mode

- Satu peranti sahaja
- Tanpa meja — pesanan bernombor (running number)
- Sesuai untuk kaunter/food truck
- Bayar terus selepas pesan

### Tukar Mode

1. Menu utama → **Setup Wizard** (footer pada RoleSelectScreen)
2. Pilih mode baru
3. Isi maklumat diperlukan
4. Tekan **Save**

> ⚠️ Menukar mode boleh menjejaskan data sedia ada. Buat backup terlebih dahulu.

---

## 13. Penyelesaian Masalah / Troubleshooting

### App terhenti / Force close
- Pastikan kebenaran (permissions) semua dibenarkan
- Pastikan Storage mencukupi
- Cuba: Settings → Apps → Warung Tom Yam → Clear Cache

### Pencetak tidak berfungsi
- Periksa sambungan Bluetooth/USB
- Pastikan pencetak dipilih dalam Settings → Printers
- Cuba "Test Print" di skrin Printers

### Pesanan tidak sampai ke dapur
- Periksa pencetak dapur disambungkan
- Periksa tetapan "Auto-print to kitchen" (Settings → Customer Order)
- Pastikan slip route kategori betul (Menu Management → Edit Category)

### Staff tidak boleh check-in
- Periksa GPS peranti staff dihidupkan
- Periksa radius dalam Café Profile mencukupi
- Pastikan "Capture Location" telah dilakukan pada peranti admin

### Laci tunai tidak buka
- Periksa "Enable cash drawer" (Switch) dalam Cash Drawer Settings
- Periksa pencetak yang disambungkan menyokong cash drawer
- Pastikan pencetak dipilih dalam "Kick through"

### Payment Monitor tidak menangkap bayaran
- Buka Settings → Notification Access → benarkan app ini
- Matikan Battery Optimization untuk app ini
- Pastikan app e-wallet yang betul dicentang dalam Monitored Apps
- Pastikan waktu semasa dalam Business Hours

### Split payment gagal
- Ini biasanya berlaku jika "Auto-print to kitchen" dimatikan
- Pesanan mesti berstatus SENT_TO_KITCHEN sebelum boleh dibayar
- Pastikan pesanan telah dihantar ke dapur sebelum cuba split

### Kehilangan akses admin
- Gunakan **Owner Recovery QR** (jika ada screenshot)
- Scan QR tersebut pada peranti baru
- Atau hubungi sokongan RAZStudio

---

## Nota Penting / Important Notes

1. **Simpan Owner Recovery QR** — ini satu-satunya cara untuk pulihkan akses jika peranti hilang
2. **Backup berkala** — Export backup setiap minggu minimum
3. **PIN Lock** — Aktifkan untuk halang akses tanpa kebenaran ke Settings
4. **Cash Drawer PIN** — Tetapkan untuk halang pekerja buka laci sewenang-wenangnya
5. **WiFi stabil** — Untuk Cloud Mode, pastikan sambungan internet sentiasa stabil
6. **Kemas kini app** — Sentiasa guna versi terkini untuk perbaikan dan ciri baru

## Status Ciri / Feature Status

| Ciri | Status | Nota |
|------|--------|------|
| Google Sign-In | 🧪 Testing | Hanya akaun tester berdaftar |
| Google Drive Backup | 🧪 Testing | Hanya akaun tester berdaftar |
| Payment Monitor / Notification Listener | 🔬 Alpha | Masih dalam pembangunan awal, mungkin tidak stabil |
| Semua ciri lain | ✅ Production | Stabil dan sedia digunakan |

---

*Dokumen ini dijana berdasarkan kod sumber aplikasi Warung Tom Yam POS v1.0.*
*Untuk sokongan teknikal, hubungi RAZStudio.*
