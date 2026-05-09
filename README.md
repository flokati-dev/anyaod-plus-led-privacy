# AnyAOD+ LED — Privacy-Relevant Source Code

This repository contains the **privacy-relevant parts** of the Android app
**AnyAOD+ LED**, published so that anyone can verify our privacy claims.

**Developer:** tmo / flokati.dev
**Contact:** dev@flokati.dev
**App packages:**
- Google Play version: `com.codecandy.blinkify`
- Samsung Galaxy Store version: `com.codecandy.anyaod`

**Full privacy policy:** https://flokati.dev/privacy

## What is AnyAOD+ LED?

AnyAOD+ LED is a notification-LED replacement and Always-On-Display app
for Android. It shows Custom-AOD elements (clock, date, weather, calendar,
to-do, media player, etc.) and notification signals (dots, labels, borders,
ripples) when the screen is off or locked.

## 🎉 Major Update (v1.5.2+): No Accessibility Permission Required!

**As of version 1.5.2, AnyAOD+ LED no longer uses or requires Accessibility
Service!**

Previous versions used Accessibility Service for rendering notification
overlays. The current version uses a **Foreground Service architecture**
instead, rendering all elements within the app's own Activity.

**Result:**
- ✅ No Accessibility permission needed
- ✅ Better privacy (one less sensitive permission)
- ✅ Easier user onboarding
- ✅ Same features, cleaner architecture

## Our Privacy Principles

### 1. Minimal Permissions

The app requires **only two permissions** for core functionality:

**Required:**
- **Notification Listener** — to know which apps have notifications
- **Foreground Service** — to display AOD while screen is off

**Optional:**
- **Internet** — ONLY if you enable the weather feature (disabled by default)

**Not used:**
- ❌ No Accessibility Service (removed in v1.5.2)
- ❌ No location access
- ❌ No camera, microphone, contacts, SMS, etc.

### 2. Minimal Internet Use

The app uses the internet for **exactly two clearly defined purposes**:

- **In-App Purchases** (handled by Google Play Billing / Samsung IAP)
- **Weather data** (ONLY if you enable the weather feature)

All other features (AOD, notifications, clock, calendar, battery, media
player, etc.) run **100% locally** on your device.

### 3. No Tracking, No Analytics

- ❌ No notification content is logged or transmitted
- ❌ No usage analytics (no Firebase Analytics, no Google Analytics)
- ❌ No crash reporting that transmits data
- ❌ No advertising partners
- ❌ No advertising IDs are read
- ❌ No fingerprinting or profiling

### 4. Open Source (Privacy-Relevant Parts)

All privacy-relevant code is in this repository. Anyone can verify our
claims file by file.

## External Services Used

There are only three:

- **[Open-Meteo](https://open-meteo.com)** — used for weather forecasts and city geocoding, but only when the weather feature is enabled. Their [terms are here](https://open-meteo.com/en/terms).
- **Google Play Billing** — used for in-app purchases in the Play Store version. See [Google's privacy policy](https://policies.google.com/privacy).
- **Samsung IAP** — used for in-app purchases in the Galaxy Store version. See [Samsung's privacy policy](https://www.samsung.com/request-desk).

That's it. No analytics, no ads, no crash reporters, no SDKs that phone home.

### About Open-Meteo

Open-Meteo is a privacy-friendly, non-commercial weather service:
- No API key required
- No registration / no user accounts
- No cookies, no tracking
- Based in Switzerland (EU adequacy decision applies under Art. 45 GDPR)

What is sent:
- The city name you entered
- The resulting latitude/longitude coordinates
- Your IP address (unavoidable for any HTTP request)

What is NOT sent:
- No device ID, no advertising ID
- No user name, no email, no contacts
- No authentication tokens (none exist)

**If you don't enable the weather feature, no connection to Open-Meteo is
ever made.**

## 🔍 Technical Verification

### A) Verify that the app CANNOT read notification content

1. Open [`AodNotificationListener.kt`](./AodNotificationListener.kt)
2. Search for `onNotificationPosted`
3. You will see:

**What IS read:**
```kotlin
val pkg = sbn.packageName   // ONLY the app name (e.g. "com.whatsapp")
val time = sbn.postTime     // ONLY the timestamp (for ordering)
```

**What is NEVER read:**
```kotlin
sbn.notification.extras       // ← NEVER called (would contain title/text)
sbn.notification.tickerText   // ← NEVER called
sbn.notification.actions      // ← NEVER called
```

Search the entire file for `extras`, `tickerText`, `getText`, `title`, or
`text` — you will find these names **only inside comments that explicitly
document that they are NOT called.** No actual code accesses them.

### B) Verify that NO Accessibility Service is used

**Previous versions (before v1.5.2)** used `LedAccessibilityService` to
render notification overlays.

**Current version (v1.5.2+)** does NOT use Accessibility Service at all.

You can verify this:
1. Open [`AndroidManifest.xml`](./AndroidManifest.xml)
2. Search for `AccessibilityService` — you will find **NONE**
3. Search for `BIND_ACCESSIBILITY_SERVICE` permission — you will find **NONE**

**Result:** The app cannot read screen content, keyboard input, or any
accessibility data because it doesn't have an Accessibility Service.

### C) Verify what the Weather Worker sends

1. Open [`OwnAodWeatherWorker.kt`](./OwnAodWeatherWorker.kt)
2. You will see only HTTPS requests to **Open-Meteo** (two URLs):
   - `https://geocoding-api.open-meteo.com/v1/search?name=...`
   - `https://api.open-meteo.com/v1/forecast?...`
3. The worker has a kill-switch at the very start:
```kotlin
   if (!AppPrefs.getOwnAodWeatherEnabled(ctx)) {
       return@withContext Result.success()  // no network request at all
   }
```

No other domains, no custom headers, no cookies, no authentication.

### D) Verify that the app does not contact any other servers

Open [`AndroidManifest.xml`](./AndroidManifest.xml) — you see only two
network-related permissions:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Search the repository for HTTP/HTTPS URLs — you will find only:
- `open-meteo.com` (weather service)
- Google/Samsung billing endpoints (handled by their SDKs)

No analytics endpoints, no tracking domains, no ad networks.

## 🛡️ Security Hardening

The notification listener uses `RECEIVER_NOT_EXPORTED` — meaning only
our own app can trigger internal broadcasts. No other app on the device
can probe or manipulate this service.

All internal broadcasts additionally use `setPackage(packageName)` to
ensure they stay within our own app.

## What this Repository Does NOT Contain

This repository contains the privacy-relevant **entry points** of the app
— the code that interacts with the Android Notification Listener API
and the internet. It does NOT contain the full app.

Specifically, classes like `NotificationRenderEngine`, `AppPrefs`,
`PureNotifState`, `UniversalNotifState`, and `DotForegroundService` are
referenced in the published code but not included. These classes handle
purely local, on-device tasks:

- **`NotificationRenderEngine`** — draws dots, labels, and borders on
  the AOD overlay. No network, no file I/O beyond local SharedPreferences.
- **`AppPrefs`** — reads/writes user settings via Android SharedPreferences.
  On-device only, never transmitted.
- **`PureNotifState` / `UniversalNotifState`** — in-memory caches shared
  between activities. No persistence, no network.
- **`DotForegroundService` / `PureModeService`** — foreground services
  that keep the overlay alive while the screen is off. No network.

The data that leaves the files in this repository is strictly limited to
what is documented in the privacy comments: package names and timestamps,
passed to in-app renderers.

### How to verify this at the network level

If you want to confirm independently that the app only contacts the
services listed above (Open-Meteo, Google Play Billing, Samsung IAP),
you can use any of the following tools:

- **Android's built-in "Private DNS" logs** (on some OEM ROMs)
- **mitmproxy** or **Charles Proxy** with a user-installed CA cert
  (on a rooted device or emulator)
- A firewall app like **NetGuard** or **RethinkDNS** to see per-app
  outbound connections in real time

You will see traffic to `*.open-meteo.com` only when the weather feature
is enabled, and to Google/Samsung billing endpoints only when making a
purchase. Nothing else.

## Files in this Repository

- [`AndroidManifest.xml`](./AndroidManifest.xml) — declares all permissions, each one documented inline with its purpose.
- [`AodNotificationListener.kt`](./AodNotificationListener.kt) — the notification listener. Proves that notification content is not read.
- [`OwnAodWeatherWorker.kt`](./OwnAodWeatherWorker.kt) — the weather worker. Proves that only Open-Meteo is contacted.
- [`OwnAodWeatherScheduler.kt`](./OwnAodWeatherScheduler.kt) — schedules the weather worker.

### Removed Files (as of v1.5.2)

The following files were present in previous versions but are **no longer
used**:

- ~~`LedAccessibilityService.kt`~~ — **Removed.** The app no longer uses
  Accessibility Service.
- ~~`build.gradle.kts`~~ — **Removed.** Contains internal version numbers
  and build configurations not relevant for privacy verification.

## Why We Publish This

Many Android apps make privacy claims that can't be verified. Because
AnyAOD+ LED requires a sensitive permission (Notification Listener),
we want our users to be able to confirm our claims themselves — line by
line, file by file.

If you find anything in this code that contradicts our privacy promises,
please open an issue. We want to know.

## Reporting Issues / Questions

- **Email:** dev@flokati.dev
- **GitHub Issues:** feel free to open an issue in this repository

## License

This code is published **for transparency and verification purposes only**.
It is part of the closed-source app "AnyAOD+ LED". Redistribution or
reuse requires written permission from the developer.

© tmo / flokati.dev — All rights reserved.
