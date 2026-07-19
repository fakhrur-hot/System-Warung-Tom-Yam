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

## Status

Phase 1 skeleton: Hilt `Application`, a single Compose `MainActivity` placeholder, English
base strings + Malay (`values-ms`). Role selection, connection flows, Table View POS,
printing, GPS attendance, etc. arrive in Phases 5–9.
