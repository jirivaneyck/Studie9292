# City Walk

Simple Android app for walking trips: records your route, lets you drop pins with a photo + note, and shares your current location or a pin via WhatsApp.

## Build

Toolchain lives in `C:\Users\VaneyckJ\android-tools` (Android SDK + Gradle); `local.properties` points to the SDK.

```
gradlew.bat assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`

Every `assembleDebug` also copies it to `CityWalk.apk` in the project root and uploads it to the home server
(`publishApk` task; needs SSH key access to `deploy@192.168.2.41`, so only on the home network).
Download it on a phone from **https://9292games.duckdns.org/apps/CityWalk.apk**. Away from home the
upload is skipped with a warning; skip it on purpose with `gradlew.bat assembleDebug -PnoPublish`.

## Install on your phone

1. On the phone, open https://9292games.duckdns.org/apps/CityWalk.apk (or copy the APK over USB).
2. Open it; allow "Install unknown apps" for the app you opened it from.
3. On first start, allow location (choose "While using the app" + "Precise") and notifications.

Or with USB debugging on: `adb install -r CityWalk.apk`

## Use

- **Start tracking**: records your route in the background (a notification shows while recording). Tap again or use the notification to stop.
- **Add pin**: pin at your current spot, or press and hold anywhere on the map to pin that spot. Optional note and camera or gallery photo. Tap a pin to view it, share it, or delete it.
- **Share a pin**: tap a pin → *Share via WhatsApp* → choose
  - *As pin*: the other person taps the link and City Walk opens on that spot (blue pin).
  - *As goal*: shows up as an orange flag for them. When they come within 50 m they get a celebration (banner + vibration, or a notification if the app is in the background) and the flag turns green.
- **Share location**: sends "I'm here: <Google Maps link>" via WhatsApp.
- Menu: *Center on me*, *Clear route* (pins are kept), *Open City Walk links directly*.

Tip: if tracking stops while the screen is off, set battery usage for City Walk to "Unrestricted" in Android settings.

## Share links

Links look like `https://9292games.duckdns.org/citywalk?lat=..&lon=..&type=goal&note=..` and are served by the page
`src/pages/citywalk.astro` in the **lingo** project (same server). On Android the page hands the link to
City Walk; without the app it shows the spot on Google Maps.

- The host is set in `app/build.gradle.kts` (`linkHost`). It is a DuckDNS name for the home server (WhatsApp only makes part of a bare-IP link clickable), so it keeps working when the home IP changes as long as DuckDNS is kept up to date.
- HTTPS and link verification come from Caddy on the server ([server/Caddyfile](server/Caddyfile)): it gets a Let's Encrypt certificate and serves `/.well-known/assetlinks.json`, so Android opens links straight in the app. The fingerprint in that file must match the key the APK is signed with (`gradlew signingReport`); builds signed with another key still work via the web page.
- If links still open the browser: menu → *Open City Walk links directly* and check `9292games.duckdns.org` is enabled.
