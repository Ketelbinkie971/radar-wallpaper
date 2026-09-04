# Radar Wallpaper

Minimal Android live wallpaper showing the latest RainViewer precipitation radar above a label-free, high-contrast map rendered locally from Natural Earth data.

## First run

1. Open **Radar Wallpaper**.
2. Tap **Allow location**, then use Android's app-permission screen to choose **Allow all the time**.
3. Choose regional scale and opacity.
4. Tap one of five labelled radar spectra and one of five miniature Sjælland map previews.
5. Tap **Set live wallpaper**, preview, and apply.

Double-tap a radar or map preset to edit it. The editor can share the current preset through Android's share sheet. Shared `*.radar.json` and `*.map.json` files can be opened with Radar Wallpaper; the import confirmation screen shows the preset type and lets the recipient choose exactly which same-type slot to overwrite. Preset files contain only the name, type and colours.

If location permission is not granted, the wallpaper defaults to Copenhagen. Location stays on-device and is only converted into the tile coordinates needed for the visible map.

## Current constraints

- RainViewer's free public API is limited to zoom 7 and historical/latest radar frames.
- Radar needs an internet connection; the base map is bundled and works offline.
- The last radar tiles are cached on disk and shown immediately while a newer frame refreshes.
- Country polygons are unwrapped across the international date line to prevent map-fill seams.
- Frames are assembled off-screen so slow network requests never hold Android's wallpaper surface open.
- This first build is deliberately static between refreshes to minimise battery use.
- RainViewer availability is best-effort.

## Build

Open the folder in Android Studio (JDK 17), or run `./gradlew assembleDebug` after generating a Gradle wrapper.

Weather data by RainViewer. Basemap geometry from Natural Earth (public domain).
