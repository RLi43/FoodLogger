# FoodLogger

A deliberately small Android app: **scan a food barcode → pick the amount → log it to Health Connect**,
so it shows up in Google Health (which reads food logs from Health Connect).
No account, no server, no ads. Product data comes from [Open Food Facts](https://world.openfoodfacts.org),
which has decent Swiss coverage.

## Flow

1. **Scan barcode** (Google code scanner from Play services, no camera permission needed).
2. Product is looked up on Open Food Facts (names in your device language, then de/fr/it/en).
   Products you logged before are re-used from the *Recent* list, also offline.
3. Choose the amount (serving, 50/100/200 g or any value) and the meal (pre-selected from the time of day).
4. **Log to Health Connect**: one `NutritionRecord` with energy, fat, saturated fat, carbs, sugar,
   fibre, protein and sodium for that portion.

If a product is missing or has no nutrition values, a per-100 g form opens (pre-filled with what is known).

The home screen lists **today's entries logged with FoodLogger** (with a small total) and lets you delete
them; right after logging, the snackbar offers **Undo**. The app only knows its own entries: it keeps the
record IDs Health Connect returns, so no read permission is needed. Google Health remains the place to see
your full day.

## Install

Install the APK from the [latest release](https://github.com/RLi43/FoodLogger/releases/latest) once
(allow "install unknown apps" for your browser/file manager). After that the app updates itself: every push
to `main` publishes a release `build-<n>`, the home screen shows *Update available*, and *Install update*
downloads it and hands it to Android's installer (allow FoodLogger to install apps the first time).

Every push, on any branch, also builds the APK in GitHub Actions (*Actions → Build → Artifacts →
foodlogger-apk*) for trying a branch before it is merged.

### Signing

CI signs with a private release key stored only in the repository's Actions secrets
`SIGNING_KEYSTORE_BASE64` (base64 of a PKCS12 keystore, key alias `foodlogger`) and `SIGNING_PASSWORD`.
Android only accepts updates signed with the same key, so keep a backup of it: losing it means
uninstalling the app (and its local Today/Recent lists) to switch to a new key. Builds without the
secrets (local builds, forks) produce an unsigned release APK; builds of `main` fail instead.

Requires Android 8.0+, Google Play services, and Health Connect (built in from Android 14; from the Play Store on older versions).

## Project layout

| Path | What |
| --- | --- |
| `core/` | Plain Kotlin/JVM build: Open Food Facts parsing, nutrient maths, barcode validation, recent list, journal of logged entries. Tested with `./gradlew -p core test`, no Android SDK needed. |
| `app/` | Android app (Jetpack Compose). `HealthConnectSink` writes the records; `FoodSink` is the seam for another destination (e.g. the Google Health cloud API). |

## Roadmap

- v2: scan the nutrition label (on-device text recognition) to pre-fill the manual form; the label parser has
  to understand German, French and Italian labels and kJ/kcal.
- Optional: Open Food Repo as a second Swiss data source (needs an API key), contributing missing products back to Open Food Facts.
