# FunkoDex — Android Funko Pop Collection Manager

A native Android app for cataloguing your Funko Pop collection.
Scan UPC barcodes, look up metadata using a layered offline-first lookup,
and manage your collection locally with Couchbase Lite.

---

## Features

- **Barcode scanner** — ML Kit camera scanner, looks up via local DB → Channel3 API → UPCitemdb
- **Manual search** — search by name when scanning isn't possible
- **Collection grid** — searchable, filterable, sortable with inline stats
- **Item detail + edit** — full detail view, inline editing, delete with confirmation
- **Reports** — cost breakdown, series completion with progress bars, want list
- **Export** — Excel (.xlsx, 4 sheets) or CSV, shared via Gmail / Files / Drive
- **Offline-first** — 23K+ Funko records bundled locally; no network needed for common items
- **Dark mode** — full Material 3 dynamic theming

---

## Project structure

```
FunkoDex/
├── app/src/main/java/com/funkodex/
│   ├── data/
│   │   ├── model/          FunkoItem, SeriesSummary, CollectionStats
│   │   ├── db/             FunkoDexDatabase (Couchbase Lite), FunkoMapper
│   │   ├── export/         CollectionExporter, ExportViewModel, ExportScreen
│   │   └── repository/     FunkoRepository — all CRUD + analytics
│   ├── network/            FunkoLookupService — layered lookup waterfall
│   ├── di/                 Hilt AppModule
│   └── ui/
│       ├── screens/
│       │   ├── scanner/    ScannerScreen + ScannerViewModel
│       │   ├── collection/ CollectionScreen + CollectionViewModel
│       │   ├── detail/     DetailScreen + DetailViewModel
│       │   └── reports/    ReportsScreen + ReportsViewModel
│       ├── theme/          Material3 theme (Funko orange palette)
│       └── FunkoDexNavHost.kt
├── app/src/test/           Unit tests (Mapper, LookupService, Stats, ViewModel)
├── app/proguard-rules.pro  Release build keep rules
├── gradle.properties
├── local.properties        SDK path + Channel3 key (DO NOT COMMIT)
└── gradle/libs.versions.toml
```

---

## Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17 |
| Android SDK | API 26+ (target 35) |
| Android device or emulator with camera | — |

---

## Setup (one-time)

### 1. Open in Android Studio
`File → Open → select the FunkoDex folder`

### 2. Fix local.properties
Android Studio will regenerate this with your SDK path. If opening manually, edit:
```
sdk.dir=/Users/YOU/Library/Android/sdk   # macOS
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk   # Windows
```

### 3. Add the Funko dataset (offline lookup)
Download: `https://raw.githubusercontent.com/kennymkchan/funko-pop-data/master/data.json`
Save as: `app/src/main/assets/funko_data.json`

Without this file the app still works — lookups just require network.

### 4. (Optional) Add Channel3 API key
Sign up free at `https://trychannel3.com`, then add to `local.properties`:
```
channel3ApiKey=your_key_here
```
The key is auto-injected via BuildConfig — never hardcoded.

---

## Installing on your phone

### Option A — USB (fastest during development)
1. Phone: Settings → Developer Options → USB Debugging → On
2. Connect USB cable
3. Android Studio: Run ▶ → select your device

### Option B — Sideload APK
1. Android Studio: Build → Build APK(s)
2. APK at `app/build/outputs/apk/debug/app-debug.apk`
3. Phone: Settings → Apps → Special access → Install unknown apps → enable for Files app
4. Transfer APK to phone, tap to install

### Option C — Play Store internal testing
1. Build → Generate Signed Bundle → AAB
2. Upload to Google Play Console → Internal Testing
3. Install via Play Store link

---

## Couchbase Lite document schema

```json
{
  "type": "funko",
  "name": "Batman (1989)",
  "series": "DC Comics",
  "seriesNumber": "#01",
  "upc": "889698123456",
  "funkoId": "batman-1989",
  "category": "Pop! Movies",
  "imageUrl": "https://...",
  "pricePaid": 14.99,
  "retailPrice": 11.99,
  "isOwned": true,
  "isExclusive": true,
  "exclusiveRetailer": "Target",
  "isVaulted": false,
  "condition": "NEAR_MINT",
  "notes": "",
  "dateAdded": "2025-09-01",
  "dateAcquired": "2025-08-31"
}
```

Indexes: `idx_series`, `idx_owned`, `idx_series_owned`, `idx_date_added`, `idx_upc`

---

## Lookup waterfall

| Layer | Source | Requires | Speed |
|-------|--------|----------|-------|
| 1 | Kenny Chan JSON (bundled) | funko_data.json in assets | Instant, offline |
| 2 | Channel3 Funko API | API key + network | ~300ms |
| 3 | UPCitemdb | Network only (100 req/day free) | ~500ms |
| 4 | Manual entry prompt | — | User action |

---

## Running tests

```bash
# Unit tests (no device needed)
./gradlew test

# Instrumented tests (device/emulator required)
./gradlew connectedAndroidTest
```

---

## Adding Capella cloud sync (future)

When ready for cloud backup / multi-device:

1. Switch to the Enterprise edition:
   ```kotlin
   implementation("com.couchbase.lite:couchbase-lite-android-ee-ktx:3.2.0")
   ```
2. Configure `Replicator` in `FunkoDexDatabase` pointing at your Capella endpoint
3. The local document schema requires no changes — Capella syncs JSON documents as-is
