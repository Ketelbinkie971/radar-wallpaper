# Radar Wallpaper

Minimal Android live wallpaper showing the latest RainViewer precipitation radar above a label-free, high-contrast map rendered locally from Natural Earth data.

## First run

1. Open **Radar Wallpaper**.
2. Tap **Allow location**, then use Android's app-permission screen to choose **Allow all the time**.
3. Choose regional scale and opacity.
4. Choose Universal Blue or the calmer WU Storm-inspired radar palette.
5. Tap **Set live wallpaper**, preview, and apply.

If location permission is not granted, the wallpaper defaults to Copenhagen. Location stays on-device and is only converted into the tile coordinates needed for the visible map.

## Current constraints

- RainViewer's free public API is limited to zoom 7 and historical/latest radar frames.
- Radar needs an internet connection; the base map is bundled and works offline.
- This first build is deliberately static between refreshes to minimise battery use.
- RainViewer availability is best-effort.

## Build

Open the folder in Android Studio (JDK 17), or run `./gradlew assembleDebug` after generating a Gradle wrapper.

Weather data by RainViewer. Basemap geometry from Natural Earth (public domain).
