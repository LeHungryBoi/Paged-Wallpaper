# Architecture

> **Note for agents:** Keep this document up to date at the end of every round of changes.

## Overview

Multi-page live wallpaper Android application built with Kotlin, Jetpack Compose (Material 3), and an OpenGL ES (`GpuWallpaperRenderer`) wallpaper service (`MultiPageWallpaperService`). Users manage per-page artwork, framing (`CropFillMode`, pan, zoom, parallax), blur/glass backdrops, darkening triggers, page transitions, launcher calibration, and diagnostic overlays.

## Directory & Module Structure

```text
.
├── AGENTS.md                       # Engineering guidelines and file-length rules
├── ARCHITECTURE.md                 # System architecture and module map (this file)
└── app/src/main/java/com/example/
    ├── MainActivity.kt             # Edge-to-edge host activity, 4-tab scaffold, editor/picker modal routing
    ├── data/
    │   ├── model/                  # WallpaperModels (PageItem, OriginalMedia, CalibrationConfig, DarkenConfig)
    │   ├── migration/              # PresetDataMigration
    │   └── repository/             # WallpaperRepository (JSON/file persistence, rendered page generation)
    ├── ui/
    │   ├── WallpaperViewModel.kt   # StateFlow orchestration for pages, presets, darken, calibration, effects
    │   ├── screens/                # StudioScreen, CropScreen (MediaEditorScreen), EffectsScreen,
    │   │                           # MediaLibraryScreen, MediaPickerScreen, CalibrationScreen,
    │   │                           # BackupScreen, FlagsScreen, SettingsScreen, AppIconLibraryScreen
    │   ├── components/             # SettingDashboardCard
    │   └── theme/                  # Material 3 color scheme, typography, theme
    ├── service/                    # MultiPageWallpaperService, WallpaperColorHelper
    ├── render/                     # GpuWallpaperRenderer, EdgeOverScrollController, DarkenTransitionController,
    │                               # EntryTransitionController, HorizontalMotionBlur, OffsetEasing, SwipeDirectionLock
    ├── transition/                 # PageTransitions
    ├── darken/                     # ExtraDimDetector
    ├── power/                      # BatterySaverMonitor
    ├── diagnostics/                # WallpaperDiagnosticsRecorder, WallpaperSignalTracker, WallpaperSignalOverlayWindow
    └── util/                       # ImageProcessingUtils
```

## Core Components

- **Host & Navigation (`MainActivity.kt`, `WallpaperViewModel.kt`)**: Hosts `MainAppScaffold` with four tabs (`Studio`, `Behavior`, `Media`, `Settings`) and overlays `MediaEditorScreen`, `MediaPickerScreen`, and `AdvancedFlagsScreen` above the scaffold.
- **Page Framing Editor (`ui/screens/CropScreen.kt`)**: Provides `MediaEditorScreen` with a unified split-resizable preview supporting both Single Page (rounded 9:19.5 viewport) and Full Image (source image with cyan static frame and amber parallax reach overlay) modes, plus controls for fill mode (`CROP`, `BLUR`, `GLASS`), blur/tint styles, zoom (`1.0x..3.0x`), parallax travel, position presets, and darkening preview.
- **Persistence & Image Pipeline (`data/repository/WallpaperRepository.kt`, `util/ImageProcessingUtils.kt`)**: Stores original media, page configurations, presets, and editor defaults; renders cropped/blurred/darkened bitmaps for wallpaper service consumption.
- **Live Wallpaper Engine (`service/MultiPageWallpaperService.kt`, `render/GpuWallpaperRenderer.kt`)**: Renders multi-page offsets, page transitions, overscroll scaling, motion blur, darken transitions, and adaptive `WallpaperColors` hints.

## Data & Control Flow

1. User imports images into `MediaLibraryScreen` and assigns them to pages via `MediaPickerScreen` or `StudioScreen`.
2. Editing a page opens `MediaEditorScreen`, which maintains draft `panX` (`-1..1`), `panY` (`-1..1`), `zoom` (`1..3`), `cropFillMode`, blur/tint, `parallaxAmount`, and `previewMode` state.
3. Saving emits an updated `PageItem` to `WallpaperViewModel.updatePageSettings`, which persists via `WallpaperRepository` and triggers `MultiPageWallpaperService` reload.

## Key Design Decisions

- **Unified Preview Gesture Isolation (`CropScreen.kt`)**: Both Single Page and Full Image preview panes use `detectTransformGestures` keyed on `(media.id, page.pageIndex)` with `rememberUpdatedState` for dynamic parameters (`displayBitmap`, `cropFillMode`, viewport dimensions) so multi-touch pinch-to-zoom never cancels mid-gesture when `zoom` updates. Mode switching between Single Page and Full Image is isolated exclusively to the bottom-right mode toggle pill badge (`crop_preview_mode_badge`, 48dp minimum touch target) so preview drags and pinches never conflict with tap toggling.
- **1:1 Direct Frame Tracking in Full-Image Preview (`calculateFullImagePreviewPan`)**: In Full Image mode, drag deltas are inverse-projected from fitted canvas pixels into source coordinates and normalized by the active pan slack (`maxPanX`/`maxPanY` in `CROP` mode, `maxOverflowX`/`maxOverflowY` scaled by `imageFitScale` in `BLUR`/`GLASS` mode) so the cyan static crop frame and amber parallax reach bounds follow the user's finger 1:1.
