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

### Android Emulator

First, start an Android emulator. Via command line:

List available emulators:
```bash
emulator -list-avds
```

Start an emulator:
```bash
emulator -avd <emulator_name>
```

Then run:
```bash
./gradlew :composeApp:installDebug
```

Build a distributable (`.deb`, `.msi`, `.dmg`):
```
./gradlew :composeApp:createDistributable
```

## Testing

### Run All Tests

Run all tests across all platforms:
```bash
./gradlew test
```

### Linux Desktop Tests

Run desktop JVM tests:
```bash
./gradlew composeApp:desktopTest
```

Run desktop UI tests with virtual display (matches CI):
```bash
xvfb-run -a ./gradlew composeApp:desktopTest
```

### Android Tests

Run Android unit tests:
```bash
./gradlew composeApp:testDebugUnitTest
```

Run Android instrumented tests (requires connected device or emulator):
```bash
./gradlew composeApp:connectedAndroidTest
```

### Test Reports

After running tests, view HTML reports at:
- Desktop tests: `composeApp/build/reports/tests/desktopTest/index.html`
- Android unit tests: `composeApp/build/reports/tests/testDebugUnitTest/index.html`
- Android instrumented tests: `composeApp/build/reports/androidTests/connected/index.html`