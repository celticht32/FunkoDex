# FunkoDex Launcher Icon — Generation Instructions

## Files in this folder

| File | Purpose |
|---|---|
| `ic_launcher_foreground.svg` | Adaptive icon foreground layer — transparent background, 108×108 dp |
| `ic_launcher_legacy.svg` | Legacy all-in-one with navy background — for reference |

---

## Method 1: Resource Manager (Android Studio Panda and newer)

This is the recommended approach for Android Studio 2025+ (Panda, Narwhal, etc.).

1. Open the FunkoDex project in Android Studio
2. Click the **Resource Manager** tab (left sidebar, or View → Tool Windows → Resource Manager)
3. Click the **+** button at the top-left of the Resource Manager panel
4. Select **Image Asset** from the dropdown
5. Set **Icon Type** to `Launcher Icons (Adaptive and Legacy)`
6. Continue with the foreground/background/options steps below

## Method 2: Right-click menu (all versions)

1. Open the FunkoDex project in Android Studio
2. Switch to **Android** view in the Project panel (dropdown at top of the panel)
3. Right-click **`app/src/main/res`** (must be the `res` folder, not a subfolder)
4. Select **New → Image Asset**
5. Set **Icon Type** to `Launcher Icons (Adaptive and Legacy)`
6. Continue with the foreground/background/options steps below

If "Image Asset" does not appear in the right-click menu, try right-clicking the
**`mipmap`** folder instead of `res`, or use Method 1 (Resource Manager) above.

---

## Foreground layer tab
- Asset Type: `Image`
- Path: browse to `ic_launcher_foreground.svg` in this folder
- Resize slider: `66%` (adjust until the outer ring sits comfortably inside the white circle preview — the ring should not touch the edge)
- Trim: `No`

## Background layer tab
- Asset Type: `Color`
- Color: `#0D1B2A` (the FunkoDex navy)

## Monochrome tab (Android 13+ themed icons)
- Leave as default (reuses the foreground layer) — or provide a separate monochrome SVG if desired

## Options tab
- Shape: `Rounded Rectangle` (or leave as system default — adaptive handles this)
- Generate Legacy: ✅ Yes
- Generate Round: ✅ Yes

Click **Next** → review the preview at all densities → **Finish**

---

## Method 3: IconKitchen (web tool — no Android Studio needed)

If Image Asset Studio gives you trouble, use Google's web-based tool:

1. Go to [IconKitchen](https://icon.kitchen)
2. Choose **Image** → upload `ic_launcher_foreground.svg`
3. Set background color to `#0D1B2A`
4. Set shape to `Circle` or `Rounded Rectangle`
5. Click **Download** → unzip the result
6. Copy the generated `mipmap-*` folders into `app/src/main/res/`, replacing the existing ones

---

## What gets generated

```
app/src/main/res/
├── mipmap-mdpi/
│   ├── ic_launcher.webp        (48×48 px)
│   └── ic_launcher_round.webp
├── mipmap-hdpi/
│   ├── ic_launcher.webp        (72×72 px)
│   └── ic_launcher_round.webp
├── mipmap-xhdpi/
│   ├── ic_launcher.webp        (96×96 px)
│   └── ic_launcher_round.webp
├── mipmap-xxhdpi/
│   ├── ic_launcher.webp        (144×144 px)
│   └── ic_launcher_round.webp
├── mipmap-xxxhdpi/
│   ├── ic_launcher.webp        (192×192 px)
│   └── ic_launcher_round.webp
└── mipmap-anydpi-v26/
    ├── ic_launcher.xml         (adaptive icon — API 26+)
    └── ic_launcher_round.xml
```

Note: newer Android Studio versions generate `.webp` instead of `.png`. Both work.

---

## Colour reference

| Role | Hex | Used for |
|---|---|---|
| Navy | `#0D1B2A` | Background layer, overall feel |
| Brass | `#B8943F` | Outer ring, inner ring, text, rule |
| Steel blue | `#5DADE2` | "COLLECTOR" tagline |

---

## Notes

- The app `android:icon` and `android:roundIcon` in `AndroidManifest.xml` already reference `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round` — no manifest changes needed after generation.
- The app will build and install without custom icons — Android uses a default green robot icon. The custom icon is cosmetic.
- If the ring looks thin at mdpi (48px), that's expected — Android's adaptive icon system masks and scales correctly from the `mipmap-anydpi-v26` XML at runtime on modern devices.
