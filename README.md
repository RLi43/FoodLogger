# FoodLogger

A deliberately small Android app: **scan a food barcode → pick the amount → log it to Health Connect**,
so it shows up in Google Health (which reads food logs from Health Connect).
No account, no server, no ads. Product data comes from [Open Food Facts](https://world.openfoodfacts.org),
which has decent Swiss coverage.

## Flow

The home screen splits adding food by kind:

- **Packaged food**: *Scan barcode*, *Search*, *Barcode photo* or *Read label*.
- **Generic food** (fruit, bakery, home cooking): *Search* or *Enter by hand*.

**Generic food search** looks through the [Swiss Food Composition Database](https://naehrwertdaten.ch)
(Federal Food Safety and Veterinary Office BLV, generic foods V 7.1, about 1,200 foods), which ships inside the
app: results update while typing and work offline. Foods you logged before come first. For common foods
(fruit, eggs, croissants, bread) the amount screen offers a typical piece or slice; these weights are the app's
own estimates, since the database has none. Names are in English, German and French; Italian can be added by passing `it=...` to the converter.

1. **Scan barcode** (Google code scanner from Play services, no camera permission needed), or
   **Barcode photo** to read it from a picture in your gallery. Square 2D codes (GS1 Data Matrix, QR codes,
   GS1 Digital Link) work too when they carry the product number.
2. Product is looked up on Open Food Facts (names in your device language, then de/fr/it/en).
   Products from *My foods* or the *Food history* are re-used directly, also offline.
3. Choose the amount (serving, 50/100/200 g or any value) and the meal (pre-selected from the time of day).
4. **Log to Health Connect**: one `NutritionRecord` with energy, fat, saturated fat, carbs, sugar,
   fibre, protein and sodium for that portion.

**Search** is for packaged food without a usable barcode (missing, damaged, or one piece from a multipack).
Optionally pick a store first (Migros, Coop, Denner, Aldi, Lidl), which keeps the list short. Your own foods
match while you type; *Search* then asks Open Food Facts for products sold in Switzerland, most scanned first
(only on request, since Open Food Facts allows about 10 searches a minute and bans clients that go over it).
Repeated searches are answered from memory, words typed after a search narrow its results without a new
request, and past 8 searches a minute the app asks you to wait. A store is matched against the
product's brands and stores, including store brands such as M-Budget or Naturaplan. Results without
nutrition values are listed last; if nothing fits, *Read the nutrition label* opens the form below.

Two lists keep foods at hand:

- **My foods**: everything you entered by hand or read from a label (including corrected values). It is your
  own database: edit or delete entries from *My foods* on the home screen. Kept until you delete them.
- **Food history**: the foods you log most, from any source, ordered by how often and how recently you logged
  them. It replaced the old 30-item recent list (which is carried over on the first start).

The **Pantry** keeps the rest of a pack you opened, for when the barcode is only on the outer package. After
choosing a portion, *Keep the rest in the pantry* (on by default for packs of two or more servings) saves what
is left; the pack size comes from Open Food Facts or the form, and when it is unknown you type how much is
left. The pantry is listed on the home screen with servings left and when the pack was opened: **Eat 1** logs
one serving for the current meal, tapping the pack logs another amount, and *Delete* removes it. A pack leaves
the pantry when it is finished, and scanning it again opens the kept pack (with *New pack* for a fresh one).
Deleting a logged entry later does not put it back into the pack; only *Undo* right after logging does.

If a product is missing or has no nutrition values, a per-100 g form opens (pre-filled with what is known).
There, **Scan nutrition label** takes a photo of the table (or *From gallery* picks one) and fills in the
values with on-device text recognition (ML Kit via Play services, nothing is uploaded). The parser reads German,
French, Italian and English labels, including the multilingual Swiss ones, takes the per-100 g column and
converts kJ to kcal when only kJ is readable. Check the values before continuing: OCR can misread digits.

The home screen shows a one-line total for today; tap it to open **Today**, the entries logged with
FoodLogger today. Tap an entry to change its amount or meal (it keeps its time), or delete it; right after
logging, the snackbar offers **Undo**. To log a food again, pick it from **Food history**. The app only
knows its own entries: it keeps the record IDs Health Connect returns, so no read permission is needed. Google Health remains the place to see
your full day.

## Install

Install the APK from the [latest release](https://github.com/RLi43/FoodLogger/releases/latest) once
(allow "install unknown apps" for your browser/file manager). After that the app updates itself: every push
to `main` publishes a release `build-<n>`, and whenever the app comes to the foreground it checks for a
newer one (at most every 5 minutes; *Check for updates* at the bottom of the home screen checks right away).
The home screen then shows *Update available*, and *Install update* downloads it and hands it to Android's
installer (allow FoodLogger to install apps the first time).

Every push, on any branch, also builds the APK in GitHub Actions (*Actions → Build → Artifacts →
foodlogger-apk*) for trying a branch before it is merged.

### Signing

CI signs with a private release key stored only in the repository's Actions secrets
`SIGNING_KEYSTORE_BASE64` (base64 of a PKCS12 keystore, key alias `foodlogger`) and `SIGNING_PASSWORD`.
Android only accepts updates signed with the same key, so keep a backup of it: losing it means
uninstalling the app (and its local Today list, Food history and My foods) to switch to a new key. Builds without the
secrets (local builds, forks) produce an unsigned release APK; builds of `main` fail instead.

Requires Android 8.0+, Google Play services, and Health Connect (built in from Android 14; from the Play Store on older versions).

## Project layout

| Path | What |
| --- | --- |
| `core/` | Plain Kotlin/JVM build: Open Food Facts parsing, nutrition label parsing, nutrient maths, barcode validation, food search, generic foods, Food history, My foods and the pantry, journal of logged entries. Tested with `./gradlew -p core test`, no Android SDK needed. |
| `tools/convert_swiss_foods.py` | Converts the database's Excel file(s) into `app/src/main/assets/generic_foods.json`; re-run it for a new database version. Each language edition passed (`en=file.xlsx de=file.xlsx …`) adds names in that language. |
| `app/` | Android app (Jetpack Compose). `HealthConnectSink` writes the records; `FoodSink` is the seam for another destination (e.g. the Google Health cloud API). |

## Roadmap

- Optional: Open Food Repo as a second Swiss data source (needs an API key), contributing missing products back to Open Food Facts.
