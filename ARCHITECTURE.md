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
    │   └── repository/             # WallpaperRepository (JSON/file persistence, rendered page generation),
    │                               # BundledSampleSeeder (fresh-launch extraction of bundled sample drawables)
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
    └── util/                       # ImageProcessingUtils, ParallaxBounds
```

## Core Components

- **Host & Navigation (`MainActivity.kt`, `WallpaperViewModel.kt`)**: Hosts `MainAppScaffold` with four tabs (`Studio`, `Behavior`, `Media`, `Settings`) and overlays `MediaEditorScreen`, `MediaPickerScreen`, and `AdvancedFlagsScreen` above the scaffold.
- **Page Framing Editor (`ui/screens/CropScreen.kt`)**: Provides `MediaEditorScreen` with a unified split-resizable preview supporting both Single Page (rounded 9:19.5 viewport) and Full Image (source image with cyan static frame and amber parallax reach overlay) modes, plus controls for fill mode (`CROP`, `BLUR`, `GLASS`), blur/tint styles, zoom (`1.0x..3.0x`), parallax travel, symmetric parallax reach toggle, position presets, and darkening preview.
- **Persistence, Sample Seeding & Image Pipeline (`data/repository/WallpaperRepository.kt`, `data/repository/BundledSampleSeeder.kt`, `util/ImageProcessingUtils.kt`, `util/ParallaxBounds.kt`)**: Stores original media, page configurations, presets, and editor defaults; on first initialization (tracked via a one-time `SharedPreferences` flag), seeds three bundled drawable samples (tall 3:4, wide 16:9, and square 1:1) into `originals/` and assigns them to the three default pages wherever a page has no user media; computes shared parallax reach bounds and renders cropped/blurred/darkened bitmaps for wallpaper service consumption.
- **Live Wallpaper Engine (`service/MultiPageWallpaperService.kt`, `render/GpuWallpaperRenderer.kt`)**: Renders multi-page offsets, page transitions, overscroll scaling, motion blur, darken transitions, and adaptive `WallpaperColors` hints.

## Data & Control Flow

1. On first initialization, `BundledSampleSeeder` materializes three bundled sample images into `originals/` and `WallpaperRepository` assigns them as the default pages (tall on Page 1, wide on Home Page 2, square on Page 3); users can also import images into `MediaLibraryScreen` and assign them to any page via `MediaPickerScreen` or `StudioScreen`.
2. Editing a page opens `MediaEditorScreen`, which maintains draft `panX` (`-1..1`), `panY` (`-1..1`), `zoom` (`1..3`), `cropFillMode`, blur/tint, `parallaxAmount`, `symmetricParallax`, and `previewMode` state.
3. Saving emits an updated `PageItem` to `WallpaperViewModel.updatePageSettings`, which persists via `WallpaperRepository` and triggers `MultiPageWallpaperService` reload.

## Key Design Decisions

- **Unified Preview Gesture Isolation & Compact Split Divider (`CropScreen.kt`)**: Both Single Page and Full Image preview panes use `detectTransformGestures` keyed on `(media.id, page.pageIndex)` with `rememberUpdatedState` for dynamic parameters (`displayBitmap`, `cropFillMode`, viewport dimensions) so multi-touch pinch-to-zoom never cancels mid-gesture when `zoom` updates. Mode switching (`crop_preview_mode_badge`, right) and the Full Image bounds legend (`crop_parallax_legend_pill`, left, shown in Full Image mode only) sit inside an edge-to-edge `36.dp` rectangular divider bar (`surfaceVariant` at 45% alpha with `1.dp` `outlineVariant` top and bottom borders) flanking a shortened centered horizontal divider line and drag pill, keeping the preview canvas unobstructed and maintaining horizontal clearance from screen edges and pills. Vertical split-resizing is isolated exclusively to a `48.dp`-tall center touch zone (`crop_split_drag_handle`) over the drag pill to prevent accidental resizing.
- **1:1 Direct Frame Tracking & Layered Overlays in Full-Image Preview (`calculateFullImagePreviewPan`)**: In Full Image mode, drag deltas are inverse-projected from fitted canvas pixels into source coordinates and normalized by the active pan slack (`maxPanX`/`maxPanY` in `CROP` mode, `maxOverflowX`/`maxOverflowY` scaled by `imageFitScale` in `BLUR`/`GLASS` mode) so the cyan static crop frame and amber parallax reach bounds follow the user's finger 1:1. The preview `Canvas` paints the `1.dp` gray container outline after the bitmap and underneath the unclipped `2.dp` amber reach and cyan static strokes (with cyan on top) so edge-aligned reach lines remain visibly anchored over the border.
- **Shared Half-Slack Parallax Reach (`util/ParallaxBounds.kt`)**: Both the editor preview and `WallpaperRepository` compute target per-side parallax travel from half of the total horizontal slack (`totalHorizontalSlack * 0.5f * parallaxAmount`), so panning the static frame leaves both left and right reach unchanged until a side meets an image border. By default each side clamps independently (`min(targetPerSide, availableSide)`); enabling `symmetricParallax` on a page locks both sides to `min(targetPerSide, availableLeft, availableRight)`.
