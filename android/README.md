# Turkish TV — Android TV App

Native Android TV app (Kotlin + Media3/ExoPlayer) for the Stremio addon in this
repo. Plays all four stream sources with TV-remote navigation.

## Install on the TV (easiest way)

1. Install **Downloader** (by AFTVnews) from the Play Store on the TV
2. Open Downloader and type exactly:
   ```
   oman-online-stremio-plugin.onrender.com/apk
   ```
   (no https:// needed)
3. Downloader fetches `turkishtv.apk` → click Install (allow "unknown sources"
   for Downloader when the TV asks)

To update later: same link always serves the latest committed APK. Settings →
Apps → Turkish TV → Uninstall first if you want a clean reinstall.

Alternative install: `adb connect <TV-IP>:5555 && adb install -r turkishtv.apk`

## Using the app

- **D-pad** navigates everything; **OK** selects
- Series grid → season tabs → episode list → player
- **Channel + / Channel –** keys switch stream sources (also switches
  automatically when a source fails)
- Positions are remembered per episode (✓ = started), with a
  **Continue** button on the home screen

## Building

The APK at `android/apk/turkishtv.apk` is what the `/apk` link serves.
To rebuild after code changes:

```bash
cd android
gradle assembleDebug        # JDK 17 + ANDROID_HOME with platform 34 required
cp app/build/outputs/apk/debug/app-debug.apk apk/turkishtv.apk
git add apk/turkishtv.apk && git commit && git push   # Render redeploys /apk
```

GitHub Actions also builds the APK on changes to `android/**` (see
`.github/workflows/build-apk.yml`) — download the artifact from the Actions
tab if you don't want a local toolchain.

## Layout

- `app/src/main/java/com/kmk/turkishtv/Config.kt` — addon base URL
- `Api.kt` — catalog/meta/stream client
- `SeriesActivity` → `EpisodesActivity` → `PlayerActivity` (Media3 ExoPlayer,
  30–120s buffers, source auto-fallback, resume)
- `WatchState.kt` — playback positions + last watched
