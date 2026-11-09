# Wallpaper Resume Stability & Zero-Disk Swiping Plan

Fix wallpaper flashing and dark glitches when returning home from full-screen apps (like YouTube), align resume positioning to the designated Home screen, add a smooth resume fade-in effect with an on/off toggle, and make launcher offset tracking strictly in-memory with zero storage writes.

### User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions from Phase 1 are integrated into this plan:
> - **Initial Resume Alignment**: When returning home from full-screen apps or after the service resumes, the wallpaper will immediately anchor to the user's **designated Home screen offset** (rather than arbitrary defaults).
> - **Smooth Resume Transition**: An elegant, rapid cross-fade (250ms) is applied by default when returning from background/full-screen apps so any visual switch is smooth and seamless. A setting toggle in the app allows turning this off if instant cut is preferred.
> - **Zero Disk Writes During Swiping**: Diagnostic offset tracking is made **100% in-memory (RAM only)**. No storage I/O occurs during desktop swiping. An in-app toggle allows enabling diagnostic capture only when calibrating.
> - **No Background Cache Purging**: Startup routines will load configuration synchronously and will never purge or invalidate bitmap caches while the live wallpaper engine is rendering.

---

## 1. Overview & Root Cause Analysis

### What Happened During YouTube Full-Screen Playback
When watching a full-screen video in YouTube:
1. Android hides the wallpaper window (`onVisibilityChanged(false)`). Under memory pressure from video playback and custom 520 DPI rendering, the wallpaper engine surface is detached or the engine state is suspended.
2. Upon returning to the home screen, `MultiPageWallpaperService` initialized its internal `currentXOffset` to a hardcoded `0.5f` (Page 2) rather than reading the user's configured Home Page offset.
3. Concurrently, `WallpaperRepository` was running an asynchronous background coroutine (`ensureStarterWallpapersExist`) that invoked `invalidateCache()` and `notifyListeners()`, which discarded bitmaps mid-frame and momentarily painted the surface dark gray (`Color.DKGRAY`).
4. Milliseconds later, the launcher resumed, sending the actual offset, causing an abrupt snap between pages with a momentary dark flash.
5. In addition, `recordObservedRawOffset` was writing `config.json` to internal flash storage on every swipe event, creating unnecessary disk wear and frame hitching.

```
[ App in Foreground: YouTube ]
             │
             ▼ (User presses Home / swipes up)
[ Android restores Wallpaper Surface ]
             │
             ├──► MultiPageEngine initializes offset to Home Screen offset
             ├──► Bitmaps already decoded in RAM (zero decode lag, no dark gray frame)
             └──► Smooth 250ms fade-in gently reveals wallpaper
```

---

## 2. Technical Architecture & Component Changes

```
┌─────────────────────────────────────────────────────────────┐
│                    WallpaperRepository                      │
│                                                             │
│  • Synchronous config loading during init (no empty states) │
│  • In-memory page bitmap cache (never purged on resume)     │
│  • Diagnostic offsets stored strictly in RAM                │
│  • ZERO disk writes during launcher swiping                 │
└──────────────────────────────┬──────────────────────────────┘
                               │ StateFlow & Memory Cache
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                 MultiPageWallpaperService                   │
│                                                             │
│  • Initial offset = calibration.homePageOffset              │
│  • onVisibilityChanged(true) triggers smooth 250ms fade-in  │
│  • Configurable resumeFadeEnabled toggle                    │
│  • Fallback to last known valid bitmap (never flashes gray) │
└──────────────────────────────┬──────────────────────────────┘
                               │ UI Controls
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                       Compose UI                            │
│                                                             │
│  • Calibration Screen: "Enable Diagnostic Capture" switch   │
│  • Effects Screen: "Smooth Resume Fade" toggle              │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Implementation Steps

### Step 1: Memory & Storage Decoupling in `WallpaperRepository`
- **Synchronous Config Initialization**: Ensure `_appState` is populated directly in the constructor from `config.json`, eliminating the race condition where `appState.pages` is temporarily empty.
- **Remove Disk Writes from `recordObservedRawOffset`**:
  - Store observed offset values in a runtime `MutableStateFlow<List<Float>>` held strictly in RAM.
  - Do **NOT** call `saveConfigInternal` or write to flash memory during launcher offset changes.
- **Safe Starter Generation**: Run starter wallpaper file checks once during first install only, without calling `invalidateCache()` or `notifyListeners()` during subsequent app/service resumes.

### Step 2: Fix Wallpaper Engine Resume & Initial Positioning
- **Home Screen Offset Anchor**:
  - In `MultiPageEngine`, initialize `currentXOffset` using `state.calibration.customOffsets.getOrNull(state.calibration.homePageIndex) ?: 0.0f`.
  - Maintain a memory-only `lastReportedOffset` in the companion object so that if the engine is recreated while still on an adjacent screen, it remembers the exact position without touching storage.
- **Smooth Resume Fade**:
  - When `onVisibilityChanged(true)` is called after the wallpaper was hidden, initiate a lightweight 250ms alpha animation from 0.0 to 1.0.
  - If the user prefers instantaneous cut without fading, respect the `resumeFadeEnabled` setting.
- **Eliminate Dark Gray Flash**:
  - If a page bitmap is temporarily decoding, retain the previous canvas contents rather than calling `canvas.drawColor(Color.DKGRAY)`.

### Step 3: UI Settings & Diagnostics Control
- **Effects Screen**:
  - Add a toggle card for **"Smooth Resume Fade"** (default: Enabled), letting the user choose between a gentle 250ms fade-in or an instant cut when exiting full-screen apps.
- **Calibration Screen**:
  - Add an **"Enable Diagnostic Logging"** switch (default: Disabled). When disabled, the engine bypasses offset recording logic altogether, saving CPU cycles during everyday swiping.
  - Display a clear indicator that offset tracking is strictly in-memory.

---

## 4. Verification & Testing

1. **Compilation Check**: Run `compile_applet` to verify clean build without warnings.
2. **Resume Behavior**: Verify that toggling visibility (simulating leaving and returning from YouTube) immediately renders the designated Home page without black frames or flashes.
3. **Smooth Fade**: Confirm that the 250ms resume fade-in transitions smoothly when returning to the home screen.
4. **Storage Audit**: Verify zero file I/O occurs when swiping across home screen pages.
