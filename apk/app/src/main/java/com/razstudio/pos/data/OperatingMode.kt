package com.razstudio.pos.data

/**
 * The three topologies this app can operate in, chosen once during Setup and persisted in
 * [AppConfigStore] under the `operating_mode` key.
 *
 * - [CLOUD]  — default; Supabase backend, customer QR web ordering. Behaviour-preserving for all
 *              existing installs: any device that has no stored `operating_mode` is treated as CLOUD.
 * - [LAN]    — no internet. The Main Admin device is the server; ordering-staff devices connect
 *              over a local wireless network.
 * - [KIOSK]  — no internet, no peers. A single admin device takes orders, prints, and reports.
 */
enum class OperatingMode { CLOUD, LAN, KIOSK }
