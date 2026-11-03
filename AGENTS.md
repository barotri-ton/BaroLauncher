# AGENTS.md

Material TV Launcher is a buttery smooth home screen for Android TV / Google TV: the most-used apps, colors that follow
the focused app, Material 3 Expressive. It is **only a launcher**: no debloat tooling, no AI, no tracking. It goes
online only for the weather, screensaver photos and the posters or channel logos an app points at.

This is the working guide for coding agents; read Hard rules and Material 3 Expressive before changing the UI. The
README links here for install, build, permissions and troubleshooting, so keep those sections. This repo is a fork of
`0xRampey/material-tv-launcher`; CI publishes its Android 9 build under `barotri-ton/material-tv-launcher`.

## Repo map

- Package `com.rampey.materialtv` (inherited from upstream) is hard-coded in `AndroidManifest.xml`, `install.sh`,
  `uninstall.sh` and every `adb` command. Don't rename it casually.
- `src/com/rampey/materialtv/` is the whole app. `HomeActivity.java` (~3.2k lines) is the only Activity and the launcher:
  Home, Apps, Continue, dock, hero, Now Playing, quick panel, tile menus and the actions behind Launcher Manager.
  Pages are view swaps in this one Activity; there are no Fragments or other Activities. It is large: make
  focused edits in the existing style, not a drive-by framework refactor.
- `ManagerPage.java` only lays out Launcher Manager; each row's action lives in `HomeActivity`.
- `Prefs.java` owns the launcher's persisted settings (`SharedPreferences("launcher")`), including folders and recent
  items as JSON. New settings usually need a `resetSettings()` entry too; see Common changes.
- UI widgets/effects: `ButtonGroup`, `CookieDrawable`, `EqualizerBadge`, `WavyProgress`, `ShapeScene`, `Palette`,
  `Motion`, `Focus`.
- Services: `HomeKeyService` (accessibility Home redirect), `MediaListener` (notification listener), `Screensaver`
  (DreamService). `AndroidManifest.xml` registers `HomeActivity` as HOME + LEANBACK_LAUNCHER + LAUNCHER; keep those
  filters or the launcher stops appearing.
- Data/network: `Weather`, `Photos`, `WatchNext`, `TvChannels`, `SessionDump`.
- No XML layouts: views are built in Java. `res/` only has drawables, `values.xml` and the accessibility config. UI
  strings are hard-coded English in Java; `values.xml` only holds the service description. `DateText` intentionally
  special-cases Vietnamese (`Ngày ... Tháng ... năm ...`); don't replace it with the platform pattern.
- No Gradle, no AndroidX/Leanback, no Compose, no tests, no lint/typecheck. `./build.sh` is the only compile check.
- `third_party/material-color-utilities` is vendored. `vendor_mcu.sh` deletes and re-copies it and uses macOS
  `sed -i ''`; run it only when deliberately updating the library, never as part of a build.

## Common changes

- **New setting:** add the field and default to `Prefs`, add a row in `managerSections()` (`HomeActivity`), implement the
  action there, and add the key to `resetSettings()` unless it is meant to survive a reset (pinned/hidden/folders do).
- **New visual:** build it in Java with the `Focus` / `Palette` / `Motion` constants; don't add XML layouts, AndroidX
  widgets or Compose.
- **New permission/service:** update `AndroidManifest.xml`, `install.sh` (and `uninstall.sh` if it changes a system
  setting), and the Privacy table below. New network use must be opt-in and must not send what the app reads.

## Build it yourself

No Gradle: `build.sh` runs aapt2 -> javac -> d8 -> zipalign -> apksigner and writes `build/material-tv-launcher.apk`.

```bash
./build.sh                         # wipes build/ first
./install.sh 192.168.1.50 [apk]    # adb over Wi-Fi; grants the permissions listed in Privacy
./uninstall.sh 192.168.1.50        # restores the TV's home screen
```

- Needs Android SDK platform 34, build-tools 34.0.0, JDK 17+ and `zip`. Set `ANDROID_HOME` if the SDK is not in the
  default location. Env overrides: `BUILD_TOOLS`, `MIN_SDK` (default 28), `VERSION_CODE` (184), `VERSION_NAME` (0.2.1),
  `KEYSTORE` / `KEYSTORE_PASS` / `KEY_ALIAS` (default: the Android debug key).
- Signing is v1 + v2 only on purpose. A build signed with the default v3 block failed to install on an Android 9
  projector (v3 is the suspected cause); don't "fix" this by enabling v3.
- `install.sh` uses `adb connect <ip>:5555`; the TV needs USB debugging or network debugging on and you must accept the
  prompt. It finds `material-tv-launcher.apk` next to the script, else `build/material-tv-launcher.apk`. It appends the
  accessibility service to the existing list; `uninstall.sh` removes only ours.
- **Windows:** `./build.sh` does not run under Git Bash as-is. It looks for extensionless `aapt2` / `d8` and calls
  `zip`; the SDK ships `aapt2.exe` / `d8.bat`, and Git Bash has no `zip`. Use WSL2 or the CI workflow; there is no
  Gradle project for Android Studio to build.
- **CI and releases:** pull requests build only. Every code push to `main` also deletes and recreates the `android9-latest`
  GitHub release with the APK, `install.sh` and `uninstall.sh`; a push to `main` is a release, not just a commit.
  Docs-only changes don't trigger the workflow. The version is grepped from `build.sh` (`VERSION_NAME:-0.2.1`), so bump
  both `VERSION_NAME` and `VERSION_CODE` there and keep that form.
- **Tests:** none. The real-TV rule below is the verification bar.
- `minSdk` is 28 (Android 9); the README's "Android 12 or newer" is tested-support guidance, not the build floor.

## Hard rules

- **Online only for the weather, screensaver photos and the posters or channel logos apps point at.** No analytics,
  update checks, telemetry or remote config. See Privacy for exactly what leaves the TV.
- **Nothing user-specific in code.** The dock comes from usage stats and installed apps; HDMI tiles come from CEC
  names. No hard-coded user app lists, TV models, IPs or names (system package exclusions are fine).
- **No brand logos we don't own.** Console tiles use Material icons on a tone of the brand hue, never a logo.
- **Nothing animates while another app is on screen.** Stop visual loops and Home-only timers in `onPause`; restart them
  in `onResume`. Now Playing may keep media-session callbacks, the low-RAM dump poll (10 s while away) and the 6 h
  forget timer running; they only update fields and never touch views.
- **Smooth on weak TVs** (4 x Cortex-A73 at 1.39 GHz, 2.4 GB RAM): animation on display frames (`postOnAnimation`,
  time-based), cost only while Home is visible, 0 janky frames in `dumpsys gfxinfo`.
- **Plain Java Views, no Gradle, no Compose, no AndroidX/Leanback.** The only dependency is the vendored
  `material-color-utilities`.
- **Test on a real TV** with full-resolution screenshots (`adb exec-out screencap -p`) of every focus state you touch.

## Material 3 Expressive, as used here

- **Color** (`Palette.java`): `SchemeFidelity` from the focused app banner's seed (`QuantizerCelebi` + `Score`, the same
  as Android wallpaper color). Use roles in their intended pairs: `primary/onPrimary`, `surface*` for backgrounds,
  `inverseSurface/inverseOnSurface` for focused fills and ink, `primaryContainer` for the background shape. Never
  hard-code white/black for focus. Tinted surfaces cross-fade with the default effects curve on retint.
- **Shape** (`Focus.java`): cards 32 dp, media (tiles, pictures, banners, album art) 20 dp, buttons fully round. Now
  Playing is a pill with radius half its measured height. Focused tiles tighten corners (to 62%); the ring stays
  concentric (radius + ring offset). Background shapes (`CookieDrawable`) morph only between library shapes: Cookie
  4/6/7/9/12, Clover 4- and 8-leaf, Sunny, Soft burst; one per app.
- **Motion** (`Motion.java`): Expressive springs via the spec's fallback curves. Spatial (movement, scale, shape) may
  overshoot: fast 350 ms for small components, default 500 ms for larger. Effects (color, opacity) never overshoot:
  fast 150 ms, default 200 ms. Don't add other curves or durations.
- **Focus** (TV): scale 1.10 with the fast spatial curve, a 3 dp ring 6 dp outside the tile that fades in, a glow in
  the app's own color (`setOutlineSpotShadowColor`), neighbors shift 8 dp aside. Buttons and nav fill with
  `inverseSurface` on focus.
- **Type**: Google Sans Flex with `'ROND' 0` (never rounded), `opsz` 24 for UI and 48 for display. The focused app name
  settles into its weight; the clock's weight follows the time of day and swells briefly on each new minute.
- **Components**: top-bar strip (`ButtonGroup`: 30 dp outer radius, 5 dp padding, 4 dp tab gaps, round icon-only tabs
  with the page name as content description). The selected page has a `primary/onPrimary` pill; focused tabs use
  `inverseSurface/inverseOnSurface` and scale 1.05; inactive tabs are transparent. Fills fade as focus/selection
  change. Now Playing uses `WavyProgress` (moving wave while playing, flat when paused, grows in from a flat line); a
  morphing shape loader appears when an app takes over 300 ms to open.
- **Launcher Manager** (`ManagerPage`): all launcher settings on one page, opened by holding OK on **Settings** or
  Quick panel -> Preferences. Categories on the left (focus selects), rows with the current value on the right; it
  also picks dock apps, hides apps in bulk and edits folders. Wallpaper can be **Random**: another bundled photo every
  15 min to 1 day, checked only while Home is up.
- **Status marks go under the tile, never on the artwork**: the playing app gets an animated equalizer
  (`EqualizerBadge`), an HDMI device that's on gets a green dot (Material green, tone 80). When the tile is focused,
  the name appears beside the mark. The label row has a fixed height so the dock never jumps.
- **Screensaver** (`Screensaver`, `ShapeScene`): generated Material You-style wallpapers (rounded library shapes in one
  tonal palette, big anchors plus small accents, a left scrim for the clock), hue and brightness from the time of day,
  a new arrangement every hour, drawn on display frames.

## Layout

- Design baseline 960 x 540 dp, 48 dp side margins (5% overscan safe area). The dock has up to eight compact 16:9 tiles across the
  width; the **TV channels** row (on by default; Launcher Manager -> TV channels) sits in a slimmer dock under it, from the
  preview programs the TV's apps publish; Now Playing is 380 dp at top-right; a Continue row (when enabled) peeks
  34 dp under the dock and scrolls up when focused.
- **Hero**: the focused item's art fills the screen behind Home under a scrim darkening toward the left and bottom,
  cross-fading on focus. A poster shows as is; an app's banner is drawn at 320 x 180 (a TV banner's own size),
  cover-cropped and scaled up — a soft wash of its colors, never a real blur.
- A chosen wallpaper replaces the hero.

## Privacy

`INTERNET` is used only for:

- **Weather** (on by default; turn it off under Launcher settings -> Weather): with a city typed, the city goes to
  Open-Meteo geocoding and then its coordinates to the forecast; with no city, the TV's rough location
  (`ACCESS_COARSE_LOCATION`) if it has one, otherwise one ipwho.is request every 6 h for the city near the home's
  public IP, then coordinates to Open-Meteo. Refreshes every 30 min (1 min retry offline).
- **Screensaver photos** (off by default; only while the screensaver runs with photos on): a size-only request to
  `picsum.photos` for a 1280 x 720 photo. The screensaver can also use the bundled wallpapers offline. The Home
  wallpaper is always bundled and offline.
- The optional **Continue** tab (off by default) reads Watch Next entries (`READ_TV_LISTINGS`) only while on and keeps
  up to 12 played items (title, app, position, duration and app-supplied artwork address) in local `SharedPreferences`,
  clearable in Launcher settings. Media-session artwork bitmaps are saved as small JPEGs in private app storage,
  limited to the remembered items and removed when history is cleared. A poster
  that is an https address loads from the address the app supplied, so that app's image server sees the TV's address,
  as the app itself would. Nothing the app reads on the TV is sent anywhere.
  While Home is visible, a content observer refreshes this row when Watch Next changes. Holding OK can remove an item;
  if Android prevents deleting another app's program, the launcher hides it locally until it is watched again.
- The **TV channels** row (on by default, turned off in Launcher Manager -> TV channels) reads the preview programs the
  TV's apps publish (`READ_TV_LISTINGS`): the name, the logo address the app gave and the intent that opens that
  channel. Only while the row is on, a logo loads from that address (kept in memory while the launcher runs), so that
  app's image server sees the TV's address. The list stays in local `SharedPreferences`; nothing else leaves the TV.

**Doc drift:** `Prefs.weatherOn()` defaults to on (`weather_off` is absent); the README's "weather off until you turn
it on" and "the launcher itself never goes online" are stale. The code above is the source of truth.

| `install.sh` switches on | Why |
|---|---|
| Accessibility service (*Home button*) | Catches the remote's Home button and the stock home screen; reads no screen content (`HomeKeyService`). |
| Usage access | Orders the dock by how much each app is used. |
| Continue watching and TV channels (`READ_TV_LISTINGS`) | Reads Watch Next only while the Continue tab is on, and the channel row only while that row is on. |
| Notification access (*Now Playing*) | Sees which app is playing; reads no notifications (`MediaListener`). |
| `DUMP` (low-RAM TVs only) | Android 9 ignores notification access on low-RAM TVs, so `SessionDump` reads `dumpsys media_session` instead. |
| `ACCESS_COARSE_LOCATION` | Weather's place when no city is typed and the TV has a location. |

The manifest also declares `REQUEST_DELETE_PACKAGES`; it only opens Android's own confirmation before an uninstall. If
you add a permission that needs an ADB grant, update `install.sh` and this table.

## Install with a coding agent

Turn on USB debugging on the TV (see Quick start), then paste this into an agent that can run commands:

```text
Install Material TV Launcher from https://github.com/barotri-ton/material-tv-launcher on my Android TV
by following its Quick start. My TV's IP address is 192.168.1.50.
```

Use the TV's own IP address (Settings -> Network & Internet).

## Quick start

You need a computer on the same Wi-Fi as the TV, with
[ADB](https://developer.android.com/tools/releases/platform-tools) installed (`adb version` works), and the TV's IP.

**1. On the TV.** Settings -> System -> About (on Android TV: Settings -> Device Preferences -> About) -> press OK on
**Android TV OS build** seven times -> go back -> Developer options -> turn on **USB debugging** (and **Network
debugging** if you see it). Note the TV's IP address under Settings -> Network & Internet.

**2. On the computer.** Download the app and the scripts from the
[latest release](https://github.com/barotri-ton/material-tv-launcher/releases/latest) into one folder, then run
`install.sh` with your TV's IP address:

```bash
curl -fLO https://github.com/barotri-ton/material-tv-launcher/releases/latest/download/material-tv-launcher.apk
curl -fLO https://github.com/barotri-ton/material-tv-launcher/releases/latest/download/install.sh
curl -fLO https://github.com/barotri-ton/material-tv-launcher/releases/latest/download/uninstall.sh
bash install.sh 192.168.1.50
```

**3. On the TV again.** When the TV shows **Allow USB debugging?**, accept it; the script waits for you. It finishes
with `Done: Material TV Launcher is on screen.` (if it says it isn't on screen yet, press Home). From then on, Home on
the remote brings it up. `bash uninstall.sh 192.168.1.50` restores the TV's own home screen.

## Screensaver setup

```bash
adb shell settings put secure screensaver_components com.rampey.materialtv/com.rampey.materialtv.Screensaver
```

Before switching, save the current one with `adb shell settings get secure screensaver_components`; to go back, `put`
that value again. It starts after the TV's idle time (`adb shell settings get system screen_off_timeout`).

## Troubleshooting

- **Home still opens Google TV.** The accessibility service is off. Run `install.sh` again, or turn on **Home button**
  under Settings -> System -> Accessibility.
- **Google TV flashes for a second after the TV boots.** Expected: Android starts the service a moment after boot, then
  this launcher takes over.
- **`adb` says unauthorized.** Accept the **Allow USB debugging?** prompt on the TV and run the script again.
- **No Now Playing card.** The app has to publish what it's playing. On a low-RAM TV (`adb shell getprop
  ro.config.low_ram` says `true`), Android never turns notification access on; `install.sh` grants `DUMP` and
  `SessionDump` reads `dumpsys media_session` (no art or length; play/pause uses the remote key).
- **No console tile.** HDMI-CEC has to be on for both the TV and the console (the TV may call it "HDMI Device Control"
  or a brand name ending in Sync or Link).

## Commits and pull requests

- Small commits, one topic each, plain messages. **No `Co-Authored-By` or other AI attribution trailers.**
- One topic per PR; say which TV you tested on. Contributions are Apache 2.0.
- For a PR you create or push, post exactly one report comment starting with `<!-- codex-pr-report -->`; edit that
  comment after later pushes instead of adding another. Include, as applicable: summary, user flow, architecture/trust
  boundaries, changes, security, build/test evidence, deploy status, rollout notes and a reviewer guide. Never claim a
  test, migration or deployment passed without verifying it. Don't commit the report as a file unless asked.
- After posting, read the PR back, verify the marked comment exists, and give links to both the PR and the comment.

## License and assets

Apache 2.0 (`LICENSE`). Bundled: material-color-utilities (Apache 2.0) and Google Sans Flex (SIL OFL 1.1). The 14
wallpapers in `assets/wallpapers` are **not** Apache 2.0: eight NASA photos (public domain) and six Wikimedia Commons
photos (CC BY-SA 3.0/4.0).
Keep `assets/wallpapers/CREDITS.md` with them. Not affiliated with or endorsed by Google.
