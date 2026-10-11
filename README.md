# SnapSortix

<img src="app_logo.png" alt="SnapSortix Logo" width="160" />

SnapSortix is an Android app that automatically organizes your screenshots using on-device AI classification.

It is designed for people who take a lot of screenshots and want instant structure without manually creating folders or tagging images one by one.

## What SnapSortix Can Do

- Detect new screenshots in the background.
- Classify screenshots locally on your phone with Gemma 4 E2B (LiteRT-LM).
- Process screenshots through a queue with live progress updates.
- Persist classification history locally so results remain available across sessions.
- Let you refresh and review classification stats from the app UI.

## Why It Helps

- Reduces screenshot clutter by auto-sorting as you capture.
- Saves time spent searching for old screenshots.
- Keeps data local to your device during classification.
- Works continuously once Auto Detect is enabled.

## Example Use Case

You are preparing for an exam and also managing day-to-day tasks.

In one week, you capture:
- LinkedIn learning posts
- Payment confirmations and bills
- OTP screens
- Booking tickets
- Maps and directions

Without SnapSortix, these screenshots become one long, noisy gallery timeline.
With SnapSortix, new screenshots are automatically categorized as they are captured, so when you need a bill, ticket, or study image later, you can find it quickly by category.

## Download

- Latest APK in repository: [SnapSortix-debug.apk](https://github.com/amoldalwai/SnapSortix/blob/master/releases/SnapSortix-debug.apk)
- Releases page: [GitHub Releases](https://github.com/amoldalwai/SnapSortix/releases)

Note: The APK is a debug build intended for testing.

## Prerequisites

### For End Users

- Android 12 or later.
- Internet connection for first-time model download.
- Sufficient free storage for the AI model download (about 2.58 GB).
- Permission to read media images.

### For Developers

- JDK 17
- Gradle 8.10.2
- Android SDK Command-line Tools
- Android platform: android-35
- Android build-tools: 35.0.0
- A physical Android 12+ device (recommended) with USB debugging

## Local Build and Install

Set ANDROID_HOME to your Android SDK path, then run:

```powershell
gradle wrapper --gradle-version 8.10.2
.\gradlew.bat assembleDebug --no-daemon
.\gradlew.bat installDebug
```

## Model Delivery

The APK does not bundle the model file. On first run, SnapSortix downloads the Gemma 4 E2B LiteRT-LM model and stores it in app-specific external Downloads.

## Gemma References

- Gemma overview: [https://ai.google.dev/gemma](https://ai.google.dev/gemma)
- Gemma model documentation: [https://ai.google.dev/gemma/docs](https://ai.google.dev/gemma/docs)
- Google AI Edge (on-device AI): [https://ai.google.dev/edge](https://ai.google.dev/edge)
- LiteRT documentation: [https://ai.google.dev/edge/litert](https://ai.google.dev/edge/litert)

If you change model variants later, update the app download URL and minimum storage guidance in this README.

## Permissions and Privacy

- SnapSortix requests READ_MEDIA_IMAGES to access screenshots.
- Classification is performed on-device after model setup.
- The app currently classifies screenshots and maintains a local classification index.

## Roadmap

- Production-safe model checksum verification.
- Storage pre-check before model download starts.
- Enhanced screenshot management workflows for move/archive actions with Android consent APIs.