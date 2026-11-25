# Full Image Preview Move & Zoom

Enable direct-manipulation move and pinch-to-zoom gestures in the page editor's full-image preview window so users can position the static crop and parallax reach frames directly on the source image and pinch to adjust zoom across both preview modes. View switching between single-page and full-image modes is isolated to the mode toggle pill badge so gestures never conflict with accidental taps.

## User Review & Critical Decisions

> [!IMPORTANT] All key interaction choices were confirmed during clarification and govern this plan.

- **Confirmed Decision 1 — Full-Image Gesture Target**: Dragging in the full-image preview moves the cyan static crop frame and amber parallax reach frame together (`panX`, `panY`), and pinching adjusts the crop zoom (`zoom` in `1.0x..3.0x`), synchronized with the existing Zoom slider and position preset buttons.
- **Confirmed Decision 2 — Direct Drag Direction**: In the full-image preview, the cyan/amber framing follows the user's finger directly (1:1 screen-space tracking across the fitted image) rather than inverting direction.
- **Confirmed Decision 3 — Single-Page Parity & View Toggling**: Pinch-to-zoom is also enabled alongside pan dragging in the single-page preview, and tapping to toggle between Single Page and Full Image modes is handled exclusively by the bottom-right mode pill badge (removing whole-preview tap toggling).

## 1. Overview & Core Concept

- **What It Does**: Transforms the page editor's full-image parallax bounds view from a passive visualization into an interactive framing canvas. Users can drag anywhere on the full-image preview to reposition the cyan static frame (and its attached amber parallax reach bounds) directly over the full source artwork, and use two-finger pinch gestures to zoom in or out in real time in both Single Page and Full Image views.
- **Target Audience / Persona**: Users fine-tuning multi-page parallax wallpapers who want to see the entire source image while placing the visible crop window and checking how far horizontal parallax will travel.
- **Key Value**: Eliminates the guesswork of switching back and forth between single-page dragging and full-image inspection, making parallax framing immediate, accurate, and gesture-native.

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Framing in Full-Image View**: User switches to Full Image mode via the mode pill badge -> drags anywhere on the full-image canvas -> the cyan static box and amber parallax reach box glide 1:1 with the finger over the stationary source image, clamping cleanly at image boundaries while live-updating the parallax travel metrics and zoom/position state below.
  2. **Pinch-to-Zoom in Either View**: User places two fingers on either the Full Image canvas or the Single Page rounded viewport -> pinches in or out -> `zoom` updates smoothly between `1.0x` and `3.0x` (matching the Zoom slider), resizing the cyan/amber frames in Full Image mode or scaling the crop/foreground in Single Page mode.
  3. **Mode Toggling via Pill Badge**: Tapping anywhere on the image preview area no longer accidentally flips preview modes; tapping the bottom-right mode pill badge (`Single · Tap for full` / `Full · Tap for page`) provides a dedicated, ripple-enabled 48dp+ touch target to switch modes.
- **Visual Identity & Theme**:
  - Preserves the existing Material 3 surface styling, sharp rectangular framing for Full Image mode (`#50DCFF` cyan static frame, `#FFB400` amber parallax reach frame, bottom-left color legend), and 18dp rounded phone-aspect viewport for Single Page mode.
  - Enhances the mode toggle pill badge with an explicit Material clickable ripple, subtle translucent border, and `minimumInteractiveComponentSize()` (48dp touch target) while keeping its compact visual pill footprint.
- **Interactive Feedback & Motion**:
  - Instant, lag-free frame updates via Compose `MutableState` during transform gestures (`detectTransformGestures`), with zero animation lag fighting the finger.
  - Immediate synchronization with the Zoom slider, parallax travel readout (`L / R dp`), and Center/Top/Bottom preset buttons.

## 3. Key Product Decisions & Trade-Offs

- **Decision 1 — Exact 1:1 Finger-to-Frame Delta Mapping vs. Fixed Normalized Multiplier**
  - *Chosen Approach*: Convert screen drag deltas (`pan.x`, `pan.y`) from canvas pixels into source-image pixels using the fitted `imageScale`, then divide by the active `maxPanX` / `maxPanY` range of the current `CropFillMode` and `zoom` to compute exact `panX` / `panY` updates (while keeping `panX` / `panY` unchanged on axes with zero pan slack, such as `1.0x` zoom when an image aspect matches the viewport).
  - *Why*: A naive constant multiplier feels too fast when zoomed in and too slow when zoomed out. Exact inverse projection guarantees the cyan frame stays locked directly under the user's finger at every zoom level and preview split height.
  - *Alternatives Considered*: Fixed `drag / size * 2f` scaling—simpler, but drifts relative to the finger depending on aspect ratio, split-screen height, and zoom.
- **Decision 2 — Combined `detectTransformGestures` vs. Separate Drag & Scale Detectors**
  - *Chosen Approach*: Replace `detectDragGestures` with `detectTransformGestures` on both the Full Image preview box and the Single Page viewport box, keying `pointerInput` cleanly with `rememberUpdatedState` for dynamic parameters (`zoom`, `cropFillMode`, `bitmap`, viewport dimensions) so ongoing multi-touch gestures are never cancelled mid-pinch when `zoom` updates!
  - *Why*: Passing `zoom` directly as a `pointerInput` key causes Compose to restart the gesture coroutine on every frame of a pinch gesture, breaking continuous zoom. Using `rememberUpdatedState` keeps the gesture stream uninterrupted across the entire pinch and drag.
  - *Alternatives Considered*: Keying `pointerInput(zoom)`—would stutter or cancel multi-touch tracking on every zoom step.
- **Decision 3 — Pill-Badge-Only Mode Toggling**
  - *Chosen Approach*: Remove `.clickable` modifiers from the outer unified preview container and the two inner preview boxes; attach `.clickable(role = Role.Button)` exclusively to the mode toggle pill badge with a 48dp minimum interactive touch target.
  - *Why*: Prevents single-finger taps or slight touch-downs on the preview image from accidentally switching modes while trying to pan or zoom, matching user preference and accessibility standards.
  - *Alternatives Considered*: Keeping outer container clickable with `pointerInput`—still risks accidental mode flips on tap-and-release inside the preview.

## 4. Technical Architecture & Data Strategy

- **Component & Gesture Flow Diagram**:

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                         MediaEditorScreen State                         │
│        panX (-1..1)  │  panY (-1..1)  │  zoom (1..3)  │  previewMode    │
└───────────┬──────────────────┬─────────────────┬───────────────┬────────┘
            │                  │                 │               │
            ▼                  ▼                 ▼               ▼
┌──────────────────────────────┐  ┌──────────────────────────────┐
│     Single-Page Preview      │  │     Full-Image Preview       │
│  • detectTransformGestures   │  │  • detectTransformGestures   │
│    (image-drag + pinch zoom) │  │    (1:1 frame-drag + zoom)   │
│  • Renders cropped/blurred   │  │  • Stationary fitted image   │
│    phone viewport            │  │  • Cyan static & Amber reach │
└──────────────────────────────┘  └──────────────────────────────┘
            ▲                                     ▲
            └──────────────┬──────────────────────┘
                           │ toggles exclusively
              ┌────────────┴────────────┐
              │  Mode Toggle Pill Badge │
              │  (48dp clickable target)│
              └─────────────────────────┘
```

- **Coordinate & State Mapping**:
  - **Shared Normalized State**: Both preview modes and the bottom control card (`Zoom` slider, `Parallax` slider, `Center / Top / Bottom` presets, and `Save`) read and mutate the same `panX`, `panY`, `zoom`, and `previewMode` state variables in the editor screen.
  - **Full-Image `CROP` Mode Pan Projection**:
    - Fitted image scale on the full-image canvas: `imageScale = min(canvasW / srcW, canvasH / srcH)`.
    - Active crop window size: `cropW = baseCropW / zoom`, `cropH = baseCropH / zoom`.
    - Maximum pan travel in source pixels: `maxPanX = ((srcW - cropW) / 2f)`, `maxPanY = ((srcH - cropH) / 2f)`.
    - Because `cropCenterX = srcW / 2f - panX * maxPanX`, moving the cyan frame right by `pan.x` canvas pixels (`pan.x / imageScale` source pixels) updates `panX = (panX - (pan.x / imageScale) / maxPanX).coerceIn(-1f, 1f)` when `maxPanX > 0f` (and identically for `panY`).
    - When `zoom` changes via pinch, `zoom = (zoom * zoomChange).coerceIn(1f, 3f)` updates both `frameLeft..frameRight` / `frameTop..frameBottom` and `reachLeft..reachRight` dynamically, keeping the frame clamped within `0..srcW` and `0..srcH`.
  - **Full-Image `BLUR` / `GLASS` Mode Pan Projection**:
    - Foreground image draw size inside the virtual phone viewport: `fitScale = min(viewportW / srcW, viewportH / srcH) * zoom`, `drawW = srcW * fitScale`, `drawH = srcH * fitScale`.
    - Maximum viewport overflow: `maxOverflowX = ((drawW - viewportW) / 2f)`, `maxOverflowY = ((drawH - viewportH) / 2f)`.
    - Because `frameLeft` moves right when `panX` decreases (`dFrameLeft = -(dPanX * maxOverflowX / fitScale)`), moving the cyan frame right by `pan.x` canvas pixels updates `panX = (panX - (pan.x / imageScale) * fitScale / maxOverflowX).coerceIn(-1f, 1f)` when `maxOverflowX > 0f` (and identically for `panY`).
  - **Single-Page Mode Transform Gestures**:
    - Uses `detectTransformGestures` with `rememberUpdatedState` so dragging continues to shift the crop/image smoothly and pinching updates `zoom = (zoom * zoomChange).coerceIn(1f, 3f)` without resetting the gesture mid-pinch.
  - **Verification Strategy**:
    - Update the existing Robolectric UI test that previously clicked the unified preview container to instead click the mode toggle pill badge (`crop_preview_mode_badge`), and verify that clicking the preview body itself no longer toggles modes.
    - Add Robolectric UI tests verifying that dragging in Full Image mode shifts saved `cropPanX` / `cropPanY` in the direct-manipulation frame direction and that multi-touch pinch gestures update `cropScale` in both Full Image and Single Page preview modes.
