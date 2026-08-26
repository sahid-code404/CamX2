# CamX2 package migration

Android install identity for CamX2 is `com.sahidcode404.camx2`.

The Kotlin/Android source namespace intentionally remains `com.sahidcode404.camx`; it is internal source organization and does not control whether Android can install CamX and CamX2 side by side.

All CamX2 OTA package checks and release endpoints must use the CamX2 identity/repository so a CamX APK can never be accepted as a CamX2 update.
