# DataGuard

A lightweight Android app that keeps your mobile data plan from vanishing. Built for fast 5G connections (like a Galaxy S21 Ultra on a limited plan) where a few minutes of video or hotspot use can burn gigabytes.

## Features

- **Speed cap (2 / 5 / 10 Mbps)** — throttle your whole phone so data lasts longer. Works without root: a local VPN routes traffic through an in-app rate limiter. Tap a speed in the app; tap Off to restore full speed.
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

## How the speed cap works (and its limits)

When you pick a speed, DataGuard starts a local VPN that routes all traffic and publishes a device-wide HTTP proxy backed by an in-app token-bucket rate limiter. DNS is forwarded natively; QUIC/UDP is blocked inside the tunnel, which makes video apps (YouTube etc.) fall back to throttled TCP. No traffic ever leaves your phone — there is no remote VPN server.

Known limits:

- **Hotspot guests are not throttled.** Android tethering bypasses VPNs at the kernel level; no non-root app can cap hotspot speed. The cap applies to apps running on the phone itself.
- The rare app that ignores the system proxy and uses raw TCP/UDP will lose connectivity while the cap is on (turn the cap off for it).
- If you use a custom "Private DNS" (DoT) provider in Android settings, set it back to Automatic while the cap is on, otherwise DNS may fail.
- You can't run another VPN (e.g. Cloudflare WARP) at the same time — Android allows one active VPN.

## Notes

- Android doesn't let normal apps cut off data automatically (that requires root). DataGuard warns you loudly instead. For a hard cutoff, also set Samsung's built-in limit: Settings → Connections → Data usage → Billing cycle and data warning → Set data limit.
- While the speed cap is on, the per-app breakdown attributes proxied traffic to DataGuard itself (Android accounts traffic to the app that sends it). Device totals stay accurate.
- Usage is measured in decimal GB (1 GB = 1,000,000,000 bytes), matching how carriers count.
- The APK is a debug-signed build produced by GitHub Actions on every push to `main`.

## Building

GitHub Actions builds the APK automatically (see `.github/workflows/build.yml`). To build locally you need Android Studio or the Android SDK + Gradle 8.7: `gradle assembleDebug`.
