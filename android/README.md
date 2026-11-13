# Turkish TV — Android TV App

Native Android TV app (Kotlin + Media3/ExoPlayer) for the Stremio addon in this
repo. Plays all four stream sources with TV-remote navigation.

## Install on the TV (easiest way)

1. Install **Downloader** (by AFTVnews) from the Play Store on the TV
2. Open Downloader and type exactly:
   ```
   is.gd/turkishtv
   ```
   (short link -> redirects to the /apk download; no https:// needed)
3. Downloader fetches `turkishtv.apk` → click Install (allow "unknown sources"
   for Downloader when the TV asks)

To update later: same link always serves the latest committed APK. Settings →
Apps → Turkish TV → Uninstall first if you want a clean reinstall.

Alternative install: `adb connect <TV-IP>:5555 && adb install -r turkishtv.apk`

## Using the app (v5 UI)

- **D-pad** navigates everything; **OK** selects; **left** from any row reaches
  the rail (Home / Search / Settings)
- Home: billboard follows whatever is focused; rows for Continue Watching,
  New this week, Turkish Series and movie genres
- Series page: Resume lands on the next episode to watch; the Season button
  opens a menu; episode cards show stills, runtime, overview and state
- Player: OK pause, ◀ ▶ seek, ▲ or MENU opens the source browser,
  **Channel + / Channel –** switch sources (also automatic when a source
  fails). A next-episode card appears in the last minute: OK plays it now,
  BACK keeps watching, and it auto-plays when the episode ends
- Movies: Play takes the best instant source; Choose source opens the sheet

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
- `Api.kt` — catalog/meta/stream client (TMDB-enriched fields are optional,
  so the app also works against an older server)
- `SeriesActivity` (home: billboard + rows) → `EpisodesActivity` (series
  page) → `PlayerActivity` (Media3 ExoPlayer with the custom controller in
  `res/layout/player_controls.xml`, 30–120s buffers, source auto-fallback,
  resume, next-episode handoff)
- `MovieDetailActivity` → `SourcePickerActivity` (grouped sheet) → player
- `Ui.kt` — shared focus treatment, image loading, formatters;
  `SnapScrollView` — row snapping on the home screen
- `WatchState.kt` — playback positions + history (with artwork for the
  Continue Watching stills)
