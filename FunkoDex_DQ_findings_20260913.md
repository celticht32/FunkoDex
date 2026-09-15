# FunkoDex — image & link data-quality findings

**Date:** 2026-09-13
**Source:** `FunkoDex_FULL_20260912_212738.zip` — full device backup, 30,831 docs
(30,179 `catalog::`, 373 `funko::`, plus price/contrib/pref/system docs).
**Trigger:** collection grid showing missing and wrong pictures.
**Method:** static analysis of the backup JSON, cross-referenced against
`CollectionScreen.kt`, `FunkoItem.kt`, `FunkoMapper.kt`, `ImageBlobRepository.kt`
and `network_security_config.xml` as they stand in `master`.

All counts below are measured from the backup, not estimated.

---

## Summary, worst first

| # | Finding | Scope | Severity |
|---|---|---|---|
| 1 | Image precedence prefers a remote URL over the local blob | 355 of 373 owned items | Medium — needless network dependency, not a rendering failure |
| 2 | `catalog::` document-id invariant violated again (DEC-018) | 11 owned docs | **High** — data corruption, previously fixed twice |
| 3 | Two owned docs encode each other's UPC in their `_id`, and carry a byte-identical image | 2 owned docs | Medium — wrong picture on at least one |
| 4 | `catalogRef` linked to the wrong catalog row | 23 of 260 linked items | Medium — the "wrong picture" reports |
| 5 | Owned items with no image source at all | 7 owned docs | Medium — unfixable in-app today |
| 6 | `CLAUDE.md` describes `network_security_config.xml` as a 10-domain allowlist | documentation | Low — but misleads future debugging |
| 7 | Catalog rows with no `imageUrl` | 9,226 of 30,179 (31%) | Low for owned items, high for browse surfaces |

Finding 2 explains every missing picture. Findings 3 and 4 explain the wrong ones.

> **Correction (same day, after review).** An earlier draft of this document
> claimed Coil 2.x cannot render a raw `ByteArray` and that no stored blob had
> ever displayed. **That was wrong.** Coil added `ByteArray` support in
> **2.1.0** ("New: Support loading `ByteArray`s", 17 May 2022) and this project
> pins **2.7.0** (`libs.versions.toml:17`), so the blob path works. The claim
> rested on a GitHub discussion that predates the 2.1.0 release, and on a
> misreading of which items were reported as blank. Finding 1 has been rewritten
> to the real, lesser issue.

---

## 1. Image precedence prefers the network over the local copy (Medium)

`CollectionScreen.kt:190`:

```kotlin
model = when {
    item.imageUrl.isNotEmpty() -> item.imageUrl.toHttpsImageUrl()
    item.userPhoto != null     -> item.userPhoto
    item.thumbnailBlob != null -> item.thumbnailBlob
    else                       -> null
},
```

The remote URL wins over the local blob, so 355 owned items that already hold a
good offline copy still fetch from the network on every render — across 33
distinct hosts, including 37 `encrypted-tbnN.gstatic.com` URLs that are Google
Image search thumbnails rather than durable links, plus shop CDNs
(booksamillion, toywiz, mercari, platoyo) with no stability guarantee.

This is **not** a rendering failure. Coil 2.7.0 supports `ByteArray`
(added in 2.1.0), the declared type of `FunkoItem.thumbnailBlob`
(`FunkoItem.kt:98`), and the `error` painter does fall back to the blob. All 357
stored blobs decode cleanly — 306 JPEG, 50 PNG, 1 WEBP, none corrupt, mean 27 KB,
max 90 KB, comfortably inside the S19 resize rule.

The costs are latency and data, not blank cards: a slow or hanging host shows an
empty tile until it fails, and 24 blobs carry a `contentType` of `image/jpeg`
while actually being PNG (harmless — decoding is by content, not by label).

**Suggested change.** Prefer the blob, fall back to the URL. 355 items then render
instantly and offline, and the remaining 18 behave exactly as they do now. Low
risk, no data migration.

## 2. The `catalog::` id invariant has been violated again (High)

DEC-018 makes this a hard invariant; S17 repaired 8 instances, S18 found 86
re-accumulated and closed the source in `DetailViewModel.toggleOwned()`.
**11 owned documents are again stored under `catalog::` ids.**

This is the direct cause of the missing pictures. An owned item on a `catalog::`
id squats the identifier the catalog's own record needs, so:

- the real catalog row cannot be created on import,
- relink's UPC index (which queries `type == "catalog"`) can never find it,
- the item inherits no catalog image, and the blobber never ran for it.

**10 of the 11 are on the image-problem list** — 7 have no image source at all,
3 have a URL but no blob. Chris's reported missing pictures are, item for item,
this set. This finding alone accounts for every blank tile.

One entry is additionally malformed: `catalog::88565.html` (*Randall vs Boo*)
carries an `.html` page-name handle of the kind `CatalogImporter` repairs on
insert.

**Fix.** Re-home all 11 to `funko::{upc|uuid}`, preserving `catalogRef`, exactly
as S17/S18 did. Then find the path that is still minting `catalog::` ids —
S18 closed `toggleOwned()`, so this is either a second path or a regression.
Until that is known, a repair is temporary.

## 3. Two documents encode each other's UPC (Medium)

| name | id encodes | upc field |
|---|---|---|
| Gotta the hutt | `889698908214` | `889698908238` |
| Grogu | `889698908238` | `889698908214` |

The two ids are transposed. These are also the only two owned items with a blob
and no `imageUrl`, and **their blobs are byte-identical — the same 6,640-byte
JPEG on both records.** That is what you would expect if the image blobber
resolved by the transposed UPC: one figure's picture was stored on both
documents, so at least one of the two displays the wrong figure.

Fix requires both halves: correct the two `_id`s, then re-fetch the image for
whichever record ends up holding the wrong one. Any id-keyed lookup on these two
also resolves to the wrong figure until the ids are corrected.

## 4. Wrong pictures come from wrong `catalogRef` links (Medium)

23 of 260 linked items point at a catalog row whose title does not resemble the
item name. Chris's reports are all in this set, and the mechanism is consistent.

**Worked example — Belle.** UPC `889698575836` has **two** catalog rows:

| row | title | has image |
|---|---|---|
| `catalog::the-beast-and-belle` | The Beast and Belle | **no** |
| `catalog::pc-7488259` | Belle #1132 | yes |

The owned item is linked to the first — a two-pack row with no image — so nothing
comes from the catalog, and the grid falls back to the item's own stored
`imageUrl`, which is a HobbyDB **"Vinyl Art Toys"** photo, not a Pop. Hence
"the Belle picture is not a Funko." The same shape repeats for UPC `889698122566`
(linked to `catalog::belle-winter`, blank, while `catalog::pc-7473906`
"Belle #238" has an image).

So wrong pictures are a three-step failure: duplicate-UPC catalog rows → relink
picks the row without an image → the item falls through to whatever junk URL was
scraped. This is the 5,272-cluster duplicate-UPC problem (12,106 rows, 40% of the
catalog) meeting the 31% of rows that carry no image.

Other clear mislinks include *Ursula* → **Maleficent** (0.12 similarity),
*Prince charming* → **Cinderella**, and *Mirage (Glow)* → **Mirabel**.
Several are variant-versus-base rather than wrong figures — *Maui* →
*Maui (Hook on Shoulder)*, *Anna with Ducks* → *Anna*, *Hades with Chess Board*
→ *Hades* — which still yield the wrong photo.

A separate image-quality case: *Lilo* (`849803046729`) links correctly to
`catalog::lilo`, but that row's image is literally `Lilo_PEZ_Dispensers_…` — a PEZ
dispenser. This is exactly the class `isFigureImage()` was added to reject in S17;
the record predates it and needs a re-enrich, not a re-link.

## 5. Seven owned items have no image source and no rescue (Medium)

No `imageUrl`, no blob, and **zero** catalog rows matching their UPC — so
"Fetch from catalog" has nothing to fetch. All seven are in the DEC-030
owned-but-not-in-catalog population.

Fixable only by a manual photo, or by catalog growth on the funko_enrich side.

## 6. `network_security_config.xml` is not an allowlist (Low)

`CLAUDE.md` states "HTTPS-only, 10-domain allowlist". Android's network security
config has no allowlist semantics: `domain-config` only overrides TLS and
cleartext settings for the domains it lists, and every other host falls through to
`base-config`, which permits HTTPS with system CAs. Walmart, gstatic,
booksamillion and the rest are **not** blocked.

Worth correcting in `CLAUDE.md` — as written it would send a future session
hunting a blocked-domain cause that does not exist.

*(Read from the config file's semantics; not verified by an on-device request.)*

## 7. Catalog image coverage (Low here, higher elsewhere)

9,226 of 30,179 catalog rows (31%) have no `imageUrl`. Not an owned-item problem,
but it is what browse, search and want-list surfaces will render as placeholders,
and it is why the wrong-row links in finding 4 produce a blank rather than a
different figure.

---

## Recommended order

**Now — code, small and high value**

1. Re-home the 11 `catalog::` owned docs, then find the path still minting them.
   This is the whole of the missing-picture problem, and it is the third
   occurrence — a repair without the source fix will not hold.
2. Flip image precedence to blob-first. Small, isolated, makes 355 items render
   offline and instantly. Worth doing but not urgent.

**Later — data, belongs on the funko_enrich side**

3. Re-enrich to replace non-figure images (the Lilo PEZ class) via
   `isFigureImage()`.
4. Decide a disambiguation rule for duplicate-UPC clusters so relink stops
   choosing image-less rows — prefer a row that has an image, prefer the
   PriceCharting row, or prompt. Candidate for its own DEC.
5. Catalog growth for the 108 owned UPCs absent from the catalog (DEC-030),
   which also resolves finding 5.

---

## Appendix — affected documents

#### A — owned docs on `catalog::` ids (11)

| name | document id | imageUrl | blob |
|---|---|---|---|
| Holiday Piglet | `catalog::holiday-piglet` | yes | **none** |
| Kristoff (Frozen 2) | `catalog::kristoff-frozen-2` | **none** | **none** |
| The Beast | `catalog::the-beast` | yes | **none** |
| Frankenstein | `catalog::pc-12372307` | **none** | **none** |
| Dracula | `catalog::pc-12374168` | **none** | **none** |
| Invisible Man | `catalog::pc-12373127` | yes | **none** |
| Stitch Unwrapping Gift | `catalog::pc-8148484` | **none** | **none** |
| Princess Fiona | `catalog::pc-7490931` | **none** | **none** |
| Mickey Mouse at the Space Mountain Attractio | `catalog::pc-7531523` | **none** | **none** |
| Space Mountain and Mickey Mouse | `catalog::pc-7531918` | **none** | **none** |
| Randall vs Boo | `catalog::88565.html` | yes | yes |

#### B — id/field UPC swap (2)

| name | id encodes | upc field |
|---|---|---|
| Gotta the hutt | `889698908214` | `889698908238` |
| Grogu | `889698908238` | `889698908214` |

#### C — suspicious `catalogRef` links (23 of 260)

| similarity | owned item | linked catalog row | row has image |
|---|---|---|---|
| 0.12 | Ursula | Maleficent (`catalog::pc-10118394`) | **no** |
| 0.25 | Funko Pop! Movies: Jurassic World Do | Dr. Alan Grant #1221 (`catalog::pc-7490597`) | yes |
| 0.27 | Merida NYCC (Official Sticker) Exclu | Merida (`catalog::pc-7488382`) | **no** |
| 0.27 | The Mandalorian [First To Market D23 | Gamorrean Fighter (`catalog::gamorrean-fighter`) | yes |
| 0.29 | Rudolph 60th Anniversary Mrs. Claus  | Mrs. Claus #1571 (`catalog::pc-7490905`) | yes |
| 0.31 | Bert on carousel horse | Bert (`catalog::pc-7531544`) | **no** |
| 0.32 | Bumble [60th Anniversary] | Bumble Metallic (`catalog::pc-7491020`) | **no** |
| 0.32 | Data with Glove Punch | Data (`catalog::pc-7489488`) | **no** |
| 0.32 | Maui | Maui (Hook on Shoulder) (`catalog::maui-hook-on-shoulder`) | yes |
| 0.35 | Phineas, Ezra, Gus | Hitchhiking Ghosts (`catalog::pc-7491151`) | **no** |
| 0.35 | Pluto on the Peoplemover | Pluto #1164 (`catalog::pc-7488301`) | yes |
| 0.37 | Hades with Chess Board | Hades (`catalog::hades`) | **no** |
| 0.38 | Chef Figment - Epcot International F | Chef Figment (`catalog::chef-figment`) | yes |
| 0.39 | Stitch on the Peoplemover | Stitch #1165 (`catalog::pc-7488302`) | yes |
| 0.40 | Jasmine | Princess Jasmine (Live Action) (`catalog::princess-jasmine-live-action`) | yes |
| 0.40 | Prince charming | Cinderella (`catalog::pc-8147862`) | **no** |
| 0.41 | Captain America | Captain America (with Electrified Mj (`catalog::captain-america-with-electrified-mjolnir-and-broken-shield`) | yes |
| 0.42 | Anna with Ducks | Anna (`catalog::pc-7488126`) | **no** |
| 0.42 | Belle | The Beast and Belle (`catalog::the-beast-and-belle`) | **no** |
| 0.44 | Dani with Binx | Dani (`catalog::pc-7488185`) | **no** |
| 0.44 | Donald Duck on the Casey Jr. Circus  | Donald Duck ToyZilla Signed Edition (`catalog::donald-duck-toyzilla-signed-edition`) | yes |
| 0.44 | Evil Queen | Evil Queen (Snow White Stained Glass (`catalog::81681.html`) | yes |
| 0.56 | Mirage (Glow) | Mirabel (`catalog::pc-7488417`) | **no** |

#### D — owned items with no renderable image source (7)

- Kristoff (Frozen 2) — `catalog::kristoff-frozen-2` — upc `889698427012`
- Frankenstein — `catalog::pc-12372307` — upc `889698928823`
- Dracula — `catalog::pc-12374168` — upc `889698928816`
- Stitch Unwrapping Gift — `catalog::pc-8148484` — upc `889698828604`
- Princess Fiona — `catalog::pc-7490931` — upc `889698811736`
- Mickey Mouse at the Space Mountain Attraction — `catalog::pc-7531523` — upc `889698453431`
- Space Mountain and Mickey Mouse — `catalog::pc-7531918` — upc `889698602464`

#### E — owned items with a URL but no blob (9)

- Holiday Piglet — `https://storage.googleapis.com/images.pricecharting.com/lknqkq32z7wmi7jx/1600.jpg`
- The Beast — `https://storage.googleapis.com/images.pricecharting.com/ydmlmfbyzahnni7v/1600.jpg`
- Invisible Man — `https://storage.googleapis.com/images.pricecharting.com/dekxdo7qbkcqznzc/1600.jpg`
- Mummy Stuart — `https://images.hobbydb.com/processed_uploads/catalog_item_photo/catalog_item_photo/image/798355/`
- Maui — `https://images.hobbydb.com/processed_uploads/catalog_item_photo/catalog_item_photo/image/463180/`
- The Child — `https://funko.com/dw/image/v2/BGTS_PRD/on/demandware.static/-/Sites-funko-master-catalog/default`
- Krrsantan — `http://mediacdn.aent-m.com/prod-img/500/99/4112699-2907740.jpg`
- Easter Stitch — `https://covers4.booksamillion.com/covers/gift/8/89/698/831/889698831123.jpg`
- Holiday Stitch with Hat — `https://mediacdn.aent-m.com/prod-img/500/55/4293455-3221257.jpg`
