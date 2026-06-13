# Funko Data Enricher
MIT License, Copyright (c) 2026 Chris Ahrendt

Three-pass enrichment pipeline that builds on your existing `funko_data.json`
(HobbyDB/Kenny Chan base catalog) and adds data from three free sources.

## Setup

```
npm install
```

## Usage

```
node enrich.js [options]
```

| Option | Default | Description |
|---|---|---|
| `--input` | `funko_data.json` | Base catalog JSON |
| `--output` | `funko_data_enriched.json` | Enriched output |
| `--delay` | `1500` | Milliseconds between funko.com page loads |
| `--chrome-path` | auto-detect | Path to Chrome/Edge executable |
| `--max-pages` | `0` (unlimited) | Stop funko.com after N pages |
| `--skip-kenny` | off | Skip Pass 1 |
| `--skip-funko` | off | Skip Pass 2 |
| `--skip-pc` | off | Skip Pass 3 |
| `--pc-limit` | `500` | Max items to look up on PriceCharting |

### Quick test (3 funko.com pages, no PriceCharting)
```
node enrich.js --max-pages 3 --skip-pc --output test_output.json
```

### Full run
```
node enrich.js --input funko_data.json --output funko_data_enriched.json
```

### Re-run to pick up new pricing only (catalog already enriched)
```
node enrich.js --input funko_data_enriched.json --output funko_data_enriched.json --skip-kenny --skip-funko --pc-limit 1000
```

---

## The Three Passes

### Pass 1 — Kenny Chan GitHub Dataset
- Source: `github.com/kennymkchan/funko-pop-data` (MIT license, ~23k records)
- Downloads the JSON directly from GitHub at run time — no scraping
- **Adds** records not in your HobbyDB base (catalog gap fill)
- **Fills** missing `imageName` on existing records where Kenny has it
- Fields added: `kennySource: true` (audit flag on new records)

### Pass 2 — funko.com Scrape
- Source: funko.com product listing pages (current inventory only)
- Paginates through `/all-funko-products/` at 48 products per page
- **Enriches** existing records with funko.com-specific data
- **Adds** any net-new records not in passes 1 or base
- Fields added: `pid`, `price`, `available`, `productUrl`, `funkoPrimaryImage`, `funkoSource`
- Note: vaulted/retired items won't appear here — that's expected

### Pass 3 — PriceCharting Market Values
- Source: pricecharting.com (eBay sold listing aggregator, free, no API key)
- Only runs on records that don't already have market pricing
- Two sub-requests per item: catalog search + product page scrape
- Uses 2.5s delay between requests to stay polite
- Fields added: `marketValueLoose`, `marketValueNew`, `pricechartingId`, `pricechartingUrl`
- Use `--pc-limit` to cap how many items to look up (full run can take hours)

---

## New Fields Added

| Field | Source | Description |
|---|---|---|
| `pid` | funko.com | Funko's internal SFCC product ID |
| `price` | funko.com | Current listed retail price |
| `available` | funko.com | Boolean — in stock on funko.com |
| `productUrl` | funko.com | Direct product page URL |
| `funkoPrimaryImage` | funko.com | Funko CDN image URL |
| `funkoSource` | funko.com | `"funko.com"` (audit trail) |
| `marketValueLoose` | PriceCharting | Secondary market value, out of box |
| `marketValueNew` | PriceCharting | Secondary market value, sealed in box |
| `pricechartingId` | PriceCharting | PriceCharting product ID |
| `pricechartingUrl` | PriceCharting | Direct PriceCharting page URL |
| `kennySource` | Kenny Chan | `true` on records added from Kenny's dataset |

Fields already in base data (`handle`, `title`, `imageName`, `series`) are
**never overwritten** — only new/missing fields are filled in.

---

## Timing Estimates

| Pass | Rate | ~12k records |
|---|---|---|
| Pass 1 Kenny Chan | Single download | < 30 seconds |
| Pass 2 funko.com | 1500ms/page, 48/page | ~10 minutes |
| Pass 3 PriceCharting | 2500ms × 2 req/item | ~14 hrs at pc-limit=10000 |

For Pass 3, use `--pc-limit 500` for a first run (~40 minutes). Run again later
with `--skip-kenny --skip-funko` to fill in more.

---

## Troubleshooting

**Pass 2 shows "0 tiles found" on every page**
funko.com changed their HTML structure. Open the page in a browser, inspect a
product card, find its CSS class or `data-*` attribute, and add it to the
`tileSelectors` array around line 140 of `enrich.js`.

**Pass 3 shows mostly "not found"**
PriceCharting title matching is fuzzy but sometimes misses. Items with special
characters, abbreviations, or unusual series names are harder to match. This is
expected — expect ~60-70% match rate on a typical catalog.

**Want to re-run after new Funko releases**
```
node enrich.js --input funko_data_enriched.json --output funko_data_enriched.json
```
Pass 1 will add new Kenny Chan records; Pass 2 will add new funko.com items;
Pass 3 will only look up records still missing pricing.

---

## Pass 2 — Chrome requirement

Pass 2 uses **puppeteer-core** + **puppeteer-extra-plugin-stealth** to drive a
real browser instance. This bypasses funko.com's bot detection, which blocks
plain HTTP requests with a 403.

**Chrome is required.** The script auto-detects it from these locations in order:
- `C:\Program Files\Google\Chrome\Application\chrome.exe`
- `C:\Program Files (x86)\Google\Chrome\Application\chrome.exe`
- `%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe`
- `C:\Program Files\Microsoft\Edge\Application\msedge.exe` (fallback)

If Chrome is not in a standard location, pass the path explicitly:
```
node enrich.js --chrome-path "C:\path\to\chrome.exe"
```

If Chrome is not installed at all, download it from https://www.google.com/chrome
