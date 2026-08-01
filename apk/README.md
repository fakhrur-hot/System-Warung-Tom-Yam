# APK — Warung Tom Yam

Single Android binary (Kotlin + Jetpack Compose + Hilt + Room) that renders either the
**Admin POS** or **Ordering staff** UI based on the role in encrypted on-device storage.

## Toolchain (pinned)

| Tool | Version |
|---|---|
| JDK (runs Gradle) | 17 |
| Gradle (wrapper) | 8.9 |
| Android Gradle Plugin | 8.7.2 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |

## First-time setup

```bash
cp local.properties.example local.properties     # then set sdk.dir
# (optional, for signed release) cp keystore.properties.example keystore.properties
```

If `gradlew` / `gradle/wrapper/gradle-wrapper.jar` are missing, bootstrap the wrapper once
(requires a Gradle install, e.g. via SDKMAN or Android Studio):

```bash
gradle wrapper --gradle-version 8.9
```

## Build

```bash
./gradlew assembleDebug      # debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease    # signed if keystore.properties present, else unsigned
```

Make the compile JDK deterministic with the Gradle Java toolchain (already set:
`kotlin { jvmToolchain(17) }`), so the build is stable regardless of `JAVA_HOME` vs PATH.

## Café Profile

A **café profile** is an optional directory you point to with `CAFE_PROFILE_DIR` in
`local.properties`. Its `res/` sub-directory is added as an extra Android resource source set and
merged over the template's at build time. Anything absent in the profile falls back to the template.
A missing or invalid path is silently ignored — it is never a build failure.

### `local.properties` keys

| Key | Default (when absent) | What it controls |
|---|---|---|
| `APPLICATION_ID` | `com.razstudio.pos` | Installed-app identity on the device. Each café gets its own value to keep its Play Store listing and update path. The source namespace (`com.razstudio.pos`) never changes. |
| `CAFE_NAME` | `RAZ POS` | App launcher name, emitted as the `app_name` string resource. |
| `DEEP_LINK_HOST` | `your-cafe.pages.dev` | Manifest placeholder for the `/join` deep-link intent filter. Set to the café's actual Cloudflare Pages domain. |
| `CAFE_PROFILE_DIR` | *(absent — template resources used)* | Path to the per-café profile directory (relative to `apk/`). When present and the directory exists, its `res/` sub-directory is merged over the template's resources. |
| `SUPABASE_URL` | *(empty — device self-configures)* | Supabase project URL, baked into `BuildConfig`. When empty the device configures itself via the owner QR or the café website. |
| `SUPABASE_ANON_KEY` | *(empty — device self-configures)* | Supabase publishable (anon) key. Never set a service-role key here. |
| `WEBSITE_URL` | *(empty)* | Cloudflare Pages URL used when generating QR codes and PDF cards. |

### Profile directory layout

```
<CAFE_PROFILE_DIR>/
└── res/
    ├── raw/
    │   └── qr_default_logo.jpg        ← receipt / QR-card logo (overrides template logo)
    ├── mipmap-mdpi/
    │   └── ic_launcher.png            ← launcher icon at each density bucket
    ├── mipmap-hdpi/
    │   └── ic_launcher.png
    ├── mipmap-xhdpi/
    │   └── ic_launcher.png
    ├── mipmap-xxhdpi/
    │   └── ic_launcher.png
    ├── mipmap-xxxhdpi/
    │   └── ic_launcher.png
    └── values/
        └── colors.xml                 ← theme palette override (TomYam50…TomYam900 ramp)
```

Only include the files you actually want to override. Every file absent in the profile is
inherited from the template.

> **Logo constraint:** `qr_default_logo.jpg` is dithered to 1-bit and scaled to ~200 px wide
> when printed on a 58 mm thermal printer. Use pure black on white with no gradients or hairlines.

### Worked example — Warung Maju

**`local.properties` snippet:**

```properties
sdk.dir=C\:\\Users\\alice\\AppData\\Local\\Android\\Sdk

APPLICATION_ID=com.warungmaju.pos
CAFE_NAME=Warung Maju
DEEP_LINK_HOST=warung-maju.pages.dev
CAFE_PROFILE_DIR=../profiles/warung-maju

SUPABASE_URL=https://abcdefghijkl.supabase.co
SUPABASE_ANON_KEY=sb_publishable_xxxxxxxxxxxxxxxxxxxx
WEBSITE_URL=https://warung-maju.pages.dev
```

**Matching profile directory tree:**

```
profiles/
└── warung-maju/          ← pointed to by CAFE_PROFILE_DIR=../profiles/warung-maju
    └── res/
        ├── raw/
        │   └── qr_default_logo.jpg   ← Warung Maju's receipt logo
        ├── mipmap-mdpi/
        │   └── ic_launcher.png
        ├── mipmap-hdpi/
        │   └── ic_launcher.png
        ├── mipmap-xhdpi/
        │   └── ic_launcher.png
        ├── mipmap-xxhdpi/
        │   └── ic_launcher.png
        ├── mipmap-xxxhdpi/
        │   └── ic_launcher.png
        └── values/
            └── colors.xml            ← Warung Maju's brand palette
```

The `profiles/` directory lives alongside `apk/` (a sibling, not inside the repo). It is not
committed to source control — only the developer building for Warung Maju keeps it locally.

### Missing profile

If `CAFE_PROFILE_DIR` is not set, or the path does not exist, the build proceeds normally using
the template's resources: the template logo, launcher icons, and default palette. No error is
raised. This is the expected outcome for a clean clone of `main` with no profile supplied.

## Status

Phase 1 skeleton: Hilt `Application`, a single Compose `MainActivity` placeholder, English
base strings + Malay (`values-ms`). Role selection, connection flows, Table View POS,
printing, GPS attendance, etc. arrive in Phases 5–9.
