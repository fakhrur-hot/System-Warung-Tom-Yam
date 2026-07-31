# Third-Party Notices and Licence Audit

**Audit date:** 2026-07-31
**Project licence:** Proprietary — see [LICENSE](LICENSE). All Rights Reserved.

## Why this file exists

This project is proprietary and sold commercially. It is built on third-party open-source
components. That combination is entirely permitted — but it carries exactly one obligation, and this
file records both the obligation and the evidence that it is satisfiable.

**The obligation:** MIT and Apache License 2.0 both require that, when you distribute a binary, you
reproduce the components' copyright notices and licence texts. Apache 2.0 states this at §4(d); MIT
states it in its single condition. This applies to closed-source distribution too — it is the one
string attached to shipping a proprietary product on a permissive stack, and the only way this
project could be in violation.

**How it is satisfied:** the app bundles `play-services-oss-licenses` and applies the
`com.google.android.gms.oss-licenses-plugin`, which enumerates the dependency set at build time and
generates an in-app "Open source licences" screen. It is generated rather than hand-maintained
deliberately: a hand-written list silently goes stale the first time someone adds a dependency.

### RESOLVED — the release APK does carry the notices (and how to check correctly)

An earlier revision of this file claimed the notices were stripped from the release build. **That was
wrong, and the mistake is worth recording because anyone auditing this will hit it too.**

The release APK contains no `res/raw/` entries at all — not even `qr_default_logo.jpg`, an asset the
app plainly uses. That is not stripping: AGP's `optimizeReleaseResources` step **shortens resource
file paths**, so `res/raw/third_party_licenses` is repackaged as something like `res/7Y`. Searching a
release APK for the original filename will always come up empty and looks exactly like a compliance
breach.

**Check by content, not by name:**

```bash
# Wrong — always returns nothing on a release APK, regardless of compliance
unzip -l app-release.apk | grep third_party

# Right — find the blob by what is inside it
python -c "
import zipfile; z = zipfile.ZipFile('app-release.apk')
print([(n, len(z.read(n))) for n in z.namelist()
       if n.startswith('res/') and b'apache.org/licenses/LICENSE-2.0' in z.read(n)])"
```

Verified 2026-07-31 with `isShrinkResources = true` (i.e. as shipped): the licence blob is present at
`res/7Y`, **436,581 bytes** — byte-identical in size to the generated `third_party_licenses`. The
oss-licenses plugin's own `keep_third_party_licenses.xml` keeps it safe from the shrinker; no
hand-written `res/raw/keep.xml` is needed, and `isShrinkResources` should stay on.

### What the generator actually produces (verified 2026-07-31)

Measured against the real release build, not assumed:

- **Release variant: 200 components, 436 KB of licence text**, emitted to
  `raw/third_party_licenses` and `raw/third_party_license_metadata` and packaged into resources.
  Confirmed present: OkHttp, Coil, Guava, Play Services, all AndroidX — and DantSu's ESCPOS library,
  which appears under its human-readable name *"Android library for ESC/POS Thermal Printer"* rather
  than its artifact id.
- **Debug variant emits a placeholder** containing only `Debug License Info`. This is normal plugin
  behaviour, not a misconfiguration — do not treat a debug build as evidence of coverage.

### Known generator gap — must stay covered by hand

**`com.google.zxing:core` is NOT picked up by the generator.** Its POM does not expose licence
metadata in a form the plugin reads, and it appears nowhere in the generated list under any name.
It is Apache 2.0, and its notice is reproduced here as the compliance record of last resort:

> ZXing ("Zebra Crossing") — Copyright ZXing authors.
> Licensed under the Apache License, Version 2.0.
> http://www.apache.org/licenses/LICENSE-2.0

This is precisely why this file exists alongside the generated screen: the generator covers the large
majority automatically, and anything it misses is recorded here rather than being quietly omitted.
When adding a dependency, check it against the generated release list and add it here if absent.

### Version pin

`play-services-oss-licenses` is pinned to **17.1.0**. Versions 17.2.2 and later pull in
`androidx.navigation3`, which requires AGP 8.9.1 while this project is on 8.7.2 —
`checkDebugAarMetadata` fails on seven transitive dependencies. Revisit when AGP is upgraded; the
licence screen needs nothing from the newer releases.

## Audit findings

Every dependency was checked for copyleft terms that would compel disclosure of our own source.
**None were found.** The stack is uniformly permissive, so a closed-source commercial product is
permitted without qualification.

### Android application

| Component | Licence | Notes |
|---|---|---|
| AndroidX — Compose, Core, Lifecycle, Activity, Navigation, Security-Crypto | Apache 2.0 | |
| AndroidX — Room, Hilt, WorkManager, CameraX | Apache 2.0 | |
| Kotlin stdlib / coroutines | Apache 2.0 | |
| `com.github.DantSu:ESCPOS-ThermalPrinter-Android` | **MIT** | Verified against the project's own `LICENSE` file, 2026-07-31 — the one non-mainstream dependency, so it was checked directly rather than assumed |
| `com.google.zxing:core` | Apache 2.0 | |
| `com.squareup.okhttp3:okhttp` | Apache 2.0 | |
| `io.coil-kt:coil-compose` | Apache 2.0 | |
| `com.google.guava:guava` | Apache 2.0 | |
| `com.google.android.gms:play-services-ads` | Proprietary (Google) | Not open source. Used under the Google Play Services Terms of Service, which permit exactly this use. Nothing to reproduce, but the Terms apply |

### Website

| Component | Licence |
|---|---|
| React, react-dom, react-router-dom | MIT |
| i18next, react-i18next | MIT |
| `qrcode` | MIT |
| `@supabase/supabase-js` | MIT |
| `jsqr` | Apache 2.0 |

### Copyleft scan

`git grep` across tracked source for GPL, AGPL, MPL, and CDDL licence text returned no matches. No
component in either the Android app or the website imposes a reciprocal-disclosure obligation.

## Code shared with other RAZStudio projects

Some components originate in other RAZStudio projects — notably the wireless-network binding logic
adapted from StudioRoom's `canon-sync` module, which is published under Apache 2.0.

RAZStudio holds the copyright on both. Apache 2.0 is a grant made **to recipients**, not a
restriction on the author, so the same code may be licensed proprietarily here. The Apache-licensed
copies already distributed remain Apache-licensed for whoever received them — that is irrevocable —
but it does not constrain this project. No conflict arises.

## Obligations that are *not* licence obligations

Recorded here because they land at the same moment and are easy to conflate with licensing:

- **Privacy policy.** Serving ads via AdMob requires a published privacy policy URL.
- **Play Data Safety declaration.** Required because the app processes order data and serves ads.
- **End-user terms.** Selling to third-party cafés means an EULA, and processing their customers'
  order data raises data-protection questions. Neither is answered by dependency licensing, and both
  warrant professional review before money changes hands.

## Maintenance

Re-run this audit whenever a dependency is added. The specific thing to check is whether the new
component is GPL, AGPL, or otherwise reciprocal — a single such dependency would compel disclosure of
our own source and is the one change that could invalidate the project's proprietary licence.
