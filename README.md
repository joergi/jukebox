# jukebox_kmp

A Kotlin Multiplatform app for browsing your Discogs vinyl collection. Targets Android, Desktop (JVM), and iOS.

## Building

### Prerequisites

1. JDK 25 (Liberica recommended, see `.sdkmanrc`)
2. Android SDK (API 36), with `ANDROID_HOME` set or `sdk.dir` in `local.properties`
3. Discogs API credentials — copy the template and fill in your keys:
   ```
   cp local.properties.template local.properties
   ```
   Then edit `local.properties`:
   ```
   discogs.consumerKey=your_key_here
   discogs.consumerSecret=your_secret_here
   ```

### Android APK

Build a debug APK:
```bash
./gradlew :composeApp:assembleDebug
```

The APK will be at:
```
composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

Send that file to your device and install it (allow installs from unknown sources in Android settings).

### Desktop (JVM)

Run locally:
```bash
./gradlew :composeApp:run
```

Build a distributable (`.deb`, `.msi`, `.dmg`):
```
./gradlew :composeApp:createDistributable
```