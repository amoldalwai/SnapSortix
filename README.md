# Screenshot Sorter

An Android 12+ Kotlin app that scans screenshots, downloads Gemma 4 E2B on first use, and classifies images locally with LiteRT-LM.

## Development setup

This project is designed for VS Code and does not require the Android Studio user interface. Install JDK 17, Gradle 8.10.2, Android SDK command-line tools, platform `android-35`, build-tools `35.0.0`, and use an Android 12+ device with USB debugging enabled.

Set `ANDROID_HOME` to the SDK directory, then run:

```powershell
gradle wrapper --gradle-version 8.10.2
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

## Model delivery

The APK does not contain the model. The user starts a first-run download of the 2.58 GB Gemma 4 E2B `.litertlm` model, stored in app-specific external Downloads. Before production release, add a checksum verification and an explicit free-storage check before enqueueing the download.

## Media permissions

The app requests `READ_MEDIA_IMAGES` only when the user scans their screenshot library. It currently classifies screenshots only. A later review-and-move workflow must call `MediaStore.createWriteRequest()` to obtain Android's user consent before moving screenshots created by other apps.