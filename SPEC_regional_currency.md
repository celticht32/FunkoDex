# SPEC: Regional Currency + Travel-Safe Pricing (FunkoDex)

Status: PROPOSED. Build after current import is validated.

License: MIT © 2026 Chris Ahrendt

---

## Goal

Funko collecting is worldwide. A price without a currency is ambiguous. Let the
user record purchases in THEIR currency, show the right symbol, and prevent
travel from silently corrupting collection values.

## Key facts (verified)

- **PriceCharting is USD by default; currency is a stored BROWSER/account
  preference, NOT request-origin based.** Their docs: "USD is the default; your
  selection is remembered in your browser." So the scraper ALWAYS gets USD
  regardless of where the HTTP call originates, unless a currency cookie is set.
  => The PriceCharting tier is TRAVEL-SAFE (always USD).
- **eBay localizes by domain/region.** ebay.co.uk returns GBP, ebay.de returns
  EUR, prices reflect the local marketplace. => The eBay fallback tier is NOT
  travel-safe; a lookup abroad can return a localized price in the wrong currency.

## Design decisions

1. **Store a currency code with every price.** Do NOT store bare numbers.
   - PriceCharting market value: always `USD`.
   - User `pricePaid` (per user copy): the user's chosen currency (e.g. the $50
     full-red Stitch is `USD 50`; a UK user's purchase is `GBP 40`).
   - Any live-tier price: tag with the currency the tier returned.

2. **Do NOT auto-convert via live FX.** Converting PriceCharting USD to the user's
   currency at live rates needs an FX API, daily-changing rates, and turns a real
   market value into a derived guess. Show USD market value as USD; let the user
   record their purchase in their own currency. A collector knows their local
   market; the app should not pretend to convert. (If a display-only "approx in my
   currency" is ever wanted, make it clearly labeled and optional — not the stored
   value.)

3. **User picks a HOME currency** (Settings). Drives the default symbol for
   pricePaid entry and the "home" baseline for the travel guard below.

## Travel-safe pricing (the important guard)

Problem: while traveling, an on-add eBay fallback could pull a local-currency price
(EUR in Europe) into a USD-based collection, silently corrupting value math.

Two-layer defense:

- **Layer 1 — pin eBay to a fixed domain.** When the app falls through to the eBay
  tier, query a FIXED eBay domain (default ebay.com / USD) regardless of the
  user's physical location, so the returned price currency matches the
  PriceCharting baseline. This removes the corruption risk for the common case
  without any user friction.

- **Layer 2 — mismatch warning.** If the device's current locale/region currency
  differs from the user's HOME currency AND a price action would record a
  local-currency value (e.g. user explicitly chooses local eBay, or enters a
  pricePaid while abroad), pop a non-blocking warning:
    "You appear to be in <region> (<localCcy>) but your home currency is
     <homeCcy>. Prices may be in <localCcy>. Record anyway?"
  Let the user proceed consciously (they may WANT to log a local purchase) rather
  than silently storing a mismatched value.

Rationale: the warning respects intent (recording a real local purchase is valid)
while preventing the silent-corruption failure mode. Domain-pinning handles the
automatic case; the warning handles the explicit/edge case.

## Data model (sketch — finalize at build)

- Every stored price becomes { amount, currency } (or a parallel `*Currency`
  field), not a bare number. Applies to: user pricePaid (per copy), any live-tier
  snapshot. PriceCharting market values are USD by definition.
- Settings: `homeCurrency` (default from device locale, user-overridable).
- eBay tier: `pinnedEbayDomain` (default ebay.com) so fallback currency is stable.

## Migration note

Existing `pricePaid` values (e.g. the $50 Stitch) are implicitly USD for a US user.
On adding the currency field, default existing values to the user's home currency
rather than leaving them ambiguous.

## Related

- SPEC_variant_hierarchy.md — level-3 user copies carry per-copy pricePaid +
  currency.
- TODO_app_autofill_prices.md — the on-ADD eBay fallback is exactly the travel-
  vulnerable path; this spec's domain-pin + warning protect it.
- PriceService waterfall (network/PriceService.kt) — where eBay domain pinning
  lives.
