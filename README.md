# DataGuard

A lightweight Android app that keeps your mobile data plan from vanishing. Built for fast 5G connections (like a Galaxy S21 Ultra on a limited plan) where a few minutes of video or hotspot use can burn gigabytes.

## Features

- **Cycle tracking** — see exactly how much mobile data you've used this billing cycle vs. your cap, with a progress bar.
- **Today's usage** — at a glance.
- **Hotspot / tethering usage** — counted separately, so you know how much your hotspot guests are costing you.
- **Per-app breakdown** — top 12 data consumers this cycle (YouTube, Instagram, etc.).
- **Pace & projection** — "at this pace you run out around Jun 23" warnings based on your daily average.
- **Threshold alerts** — notifications at 50%, 80%, 95%, and 100% of your cap, checked every 30 minutes in the background.

## Install on your phone

1. Go to this repo's **[Releases](../../releases)** page on your phone.
2. Download `DataGuard.apk` from the latest release.
3. Open the downloaded file. Android will ask you to allow installs from your browser — allow it.
4. Open DataGuard and tap **Grant usage access** → find DataGuard in the list → enable it. (This special permission is required to read network statistics; the app never touches the internet itself.)
5. Allow notifications when prompted.
6. Set your **monthly cap in GB** and the **day of the month your plan resets**, then tap Save.

## Notes

- Android doesn't let normal apps cut off data automatically (that requires root). DataGuard warns you loudly instead. For a hard cutoff, also set Samsung's built-in limit: Settings → Connections → Data usage → Billing cycle and data warning → Set data limit.
- Usage is measured in decimal GB (1 GB = 1,000,000,000 bytes), matching how carriers count.
- The APK is a debug-signed build produced by GitHub Actions on every push to `main`.

## Building

GitHub Actions builds the APK automatically (see `.github/workflows/build.yml`). To build locally you need Android Studio or the Android SDK + Gradle 8.7: `gradle assembleDebug`.
