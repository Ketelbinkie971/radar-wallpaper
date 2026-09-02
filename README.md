# Radar Wallpaper

Minimal Android live wallpaper showing the latest RainViewer precipitation radar above a subdued OpenStreetMap map, centred on the device's last known location.

## First run

1. Open **Radar Wallpaper**.
2. Tap **Allow location** and grant precise or approximate location.
3. Choose regional scale and opacity.
4. Tap **Set live wallpaper**, preview, and apply.

If location permission is not granted, the wallpaper defaults to Copenhagen. Location stays on-device and is only converted into the tile coordinates needed for the visible map.

## Current constraints

- RainViewer's free public API is limited to zoom 7 and historical/latest radar frames.
- Map and radar need an internet connection.
- This first build is deliberately static between refreshes to minimise battery use.
- OpenStreetMap and RainViewer availability are best-effort.

## Build

Open the folder in Android Studio (JDK 17), or run `./gradlew assembleDebug` after generating a Gradle wrapper.

Weather data by RainViewer. Map © OpenStreetMap contributors.
