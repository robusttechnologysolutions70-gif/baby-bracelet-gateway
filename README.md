# Baby Bracelet Gateway (Android)

This Android app forms the phone gateway between the two bracelets:

1. It scans for `BABY-BRACELET` and `MOTHER-BRACELET`.
2. It connects to both devices.
3. It subscribes to the baby's temperature notifications.
4. It automatically writes each received two-byte temperature value to the mother's bracelet.

## Test it

1. Flash and power both bracelets.
2. Open the `mother-gateway-android` folder in Android Studio.
3. Allow Android Studio to complete Gradle sync, then run the `app` configuration on a physical Android phone.
4. Grant the requested Nearby devices / Bluetooth permission.
5. Tap **Connect bracelets** and keep the app open while testing.

The screen shows both BLE connection states and the latest temperature. Values of 37.5 C or above display an alert and cause the mother bracelet to flash its alert LED and vibrate.

The application requires Android 6.0 or newer. Android 12 or newer uses the Nearby devices permission; older Android versions request location permission because BLE scanning requires it.

## Build online without Android Studio

This project includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`.

1. Create a GitHub repository in your browser.
2. Upload the contents of this `mother-gateway-android` folder to that repository.
3. Open **Actions** in the repository, select **Build Android APK**, and choose **Run workflow**.
4. When the run succeeds, open it and download the `baby-bracelet-gateway-debug-apk` artifact.
5. Extract the downloaded zip and install `app-debug.apk` on your Android phone. You may need to allow installs from your browser or Files app.

The debug APK is for direct testing; it is not Play Store signed.
"# baby-bracelet-gateway" 
