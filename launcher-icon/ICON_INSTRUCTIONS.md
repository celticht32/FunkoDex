# FunkoDex Launcher Icon — Generation Instructions

## Files in this folder

| File | Purpose |
|---|---|
| `ic_launcher_foreground.svg` | Adaptive icon foreground layer — transparent background, 108×108 dp |
| `ic_launcher_legacy.svg` | Legacy all-in-one with navy background — for reference |

---

## Generating the mipmap PNGs (Android Studio)

This takes about 90 seconds.

1. Open the FunkoDex project in Android Studio
2. In the Project panel, right-click **`app/src/main/res`**
3. Select **New → Image Asset**
4. Set **Icon Type** to `Launcher Icons (Adaptive and Legacy)`

### Foreground layer tab
- Asset Type: `Image`
- Path: browse to `ic_launcher_foreground.svg` in this folder
- Resize slider: `66%` (adjust until the outer ring sits comfortably inside the white circle preview — the ring should not touch the edge)
- Trim: `No`

### Background layer tab
- Asset Type: `Color`
- Color: `#0D1B2A` (the FunkoDex navy)

### Legacy tab
- Generate: ✅ checked for all densities

### Options tab
- Shape: `Rounded Rectangle` (or leave as system default — adaptive handles this)

5. Click **Next** → review the preview at all densities → **Finish**

Android Studio generates:
```
app/src/main/res/
├── mipmap-mdpi/
│   ├── ic_launcher.png         (48×48 px)
│   └── ic_launcher_round.png
├── mipmap-hdpi/
│   ├── ic_launcher.png         (72×72 px)
│   └── ic_launcher_round.png
├── mipmap-xhdpi/
│   ├── ic_launcher.png         (96×96 px)
│   └── ic_launcher_round.png
├── mipmap-xxhdpi/
│   ├── ic_launcher.png         (144×144 px)
│   └── ic_launcher_round.png
├── mipmap-xxxhdpi/
│   ├── ic_launcher.png         (192×192 px)
│   └── ic_launcher_round.png
└── mipmap-anydpi-v26/
    ├── ic_launcher.xml         (adaptive icon — API 26+)
    └── ic_launcher_round.xml
```

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
- The app will not install on a physical device until at least `mipmap-xxxhdpi/ic_launcher.png` exists.
- If the ring looks thin at mdpi (48px), that's expected — Android's adaptive icon system masks and scales correctly from the `mipmap-anydpi-v26` XML at runtime on modern devices.
