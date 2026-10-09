# Changelog

## 1.10 — 2026-10-10 (legacy code 13, modern code 14)
- Version information moved to a single source (`app/build.gradle`): Gradle and
  `build.sh` can no longer drift apart (they used to disagree — Gradle said
  1.7/8 while releases were 1.9/11-12).
- Modern flavor now targets **API 35** (Android 15) instead of API 37, and the
  build pins `build-tools;35.0.0` + `platforms;android-35`. This is required for
  reproducible builds (F-Droid) and for building on standard SDK images.
- `build.sh` no longer silently falls back to "whatever SDK is installed": it
  fails fast if the pinned build-tools/platform are missing.
- Added `fastlane/` metadata (title, descriptions, changelog, icon, screenshot)
  for F-Droid.

## 1.9 — 2026-10-03 (legacy code 11, modern code 12)
- Alphabet index bar speaks Hebrew: sections `#`, `A–Z`, `А–Я`, `א–ת`.
  Final forms (ך ם ן ף ץ) fold into their base letter's section.
  Field-proven on a device with mixed Hebrew/English/Russian app names.
- Index bar thins its drawn labels when too many sections fit the screen:
  every N-th letter is painted, but touch still maps to all sections.

## 1.8 — 2026-10-03 (legacy code 9, modern code 10)
Field feedback release (first external user):
- Search pill became a real EditText: live filter over installed apps by
  label and package name, Unicode casefold, works with any IME/language.
  IME search action launches the top local match, or a web search when
  nothing matched on the device.
- Alphabet index bar: `#`, `A–Z`, `А–Я`; tap or drag to jump to a section.
  Name sort is section-then-collator, so bar and grid always agree.
  Bar shows only in name-ascending sort without an active query.
- Long-press clock → *Switch default launcher…*: `HOME_SETTINGS` →
  *Manage default apps* → own app card with clear-defaults instructions
  (the only working path on Android 6).

## 1.7 — 2026-10-02 (legacy code 7, modern code 8)
- Legacy API audit against `api-versions.xml`: `LauncherApps.getProfiles()`
  (API 26) replaced with `UserManager.getUserProfiles()` (API 17);
  `isQuietModeEnabled` placed under an `SDK_INT >= 24` guard.
  Result: no references newer than API 21 in the legacy dex.

## 1.6 — 2026-10-02 (legacy code 6, modern code 7)
- Work profiles: `LauncherApps.getActivityList(null, user)`, badged icons,
  `startMainActivity`, `startAppDetailsActivity`, `LauncherApps.Callback`;
  quiet profiles skipped; no *Uninstall* for work tiles; per-user launch counters.

## 1.5 — 2026-10-02 (legacy code 5, modern code 6)
- Six sort modes: name A→Z / Z→A, install date, update date, launch count
  (counter in prefs), APK size. Secondary key is always the name.
  Picker: long-press the clock → *Sort*.

## 1.4 — 2026-10-02 (modern code 5)
- Second flavor *modern* (targetSdk 37): `<queries>` MAIN/LAUNCHER for package
  visibility on Android 11+, `RECEIVER_NOT_EXPORTED` under SDK guard,
  `REQUEST_DELETE_PACKAGES`, edge-to-edge via `fitsSystemWindows`.

## 1.3 — 2026-10-02 (legacy code 4)
- Package add/remove/change receivers moved from onResume/onPause to
  onCreate/onDestroy with immediate reload: broadcasts fired while another
  app was in front no longer get missed.

## 1.2 — 2026-10-02 (legacy code 3)
- Root layout FrameLayout → LinearLayout: the grid can no longer overlap the
  header (clock/search) at any font scale.
- Long-press the clock: hide/show the search pill (persisted).

## 1.1 — 2026-10-01 (code 2)
- Header/grid overlap fix for the search pill (structural, see 1.2 for the
  final layout change).

## 1.0 — 2026-10-01 (code 1)
- First build: 4-column grid, alphabetical, clock+date, search pill,
  long-press menu (app info / uninstall), zero permissions, zero dependencies.
