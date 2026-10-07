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

Every push builds a debug APK in GitHub Actions (*Actions → Build → Artifacts → foodlogger-debug-apk*).
Download it, unzip, and install it on the phone (allow "install unknown apps" for your browser/file manager).
Builds are signed with the committed `app/debug.keystore`, so new builds install over old ones.

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
