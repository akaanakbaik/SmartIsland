# Privacy Policy

Last updated: October 27, 2026

Smart Island processes notifications and renders its island on your Android device. Optional network features contact the services listed below. This document describes the current source code; older installed versions can behave differently.

## Information The App Processes

When you enable notification listener access, Smart Island can process notification metadata from other apps so it can display the floating island experience. This may include:

- App name and package name
- Notification title and text
- Notification icons or large images
- Notification action labels and action intents
- Call and media notification categories
- Media metadata such as artwork, playback state, duration, and position when available

The app also stores island settings, app selections, imported fonts, weather preferences and caches. Optional calendar widgets read event titles and times after calendar permission is granted. Location permission lets weather use a last-known location and lets Wi-Fi cards read the network name. Weather coordinates are rounded to one decimal place before they are stored or sent.

Accessibility access lets the overlay inspect window/package information, identify the foreground app, handle its back gesture and perform selected system actions. Usage access can identify the foreground app when accessibility cannot. These grants are powerful; enable only features you want.

## How Information Is Used

Notification data is used to render the island UI, show demo or real notification states, open notification content, dismiss notifications, and update media/call presentation.

Settings are used to remember your preferred island appearance and position.

## Data Sharing

Smart Island requests Android's `INTERNET` permission. Network access is enabled by default and can be turned off under **Network Access & Release Checks**. The current code uses these services:

- GitHub API: release, contributor, commit and repository queries. Requests include a user-agent and reveal the connecting IP address to GitHub.
- Open-Meteo: weather and optional air-quality queries include rounded coordinates and requested weather fields. Manual city searches send the entered query and language to Open-Meteo's geocoding service.
- `ipwho.is` and `ipapi.co`: IP-based location for weather fallback or VPN connection cards. The receiving service sees the IP used for the request.
- A custom HTTPS weather provider, if you configure one: rounded coordinates, requested weather fields and your optional API key are sent to that provider. Air-quality data is only available with the Open-Meteo provider.

Turning network access off prevents new in-app queries, including manual city searches. A request already sent cannot be recalled; a blocking request may finish after the setting changes. External links and APK downloads opened in a browser are controlled by that browser, not this setting.

The inspected source has no advertising or analytics SDK and no network client that sends notification content to these services. Diagnostic export and Android backup are described separately below. External services have their own privacy policies.

External links in the app may open GitHub, email, or social/profile pages in another app or browser. Those external apps and services have their own privacy practices.

## Data Retention

Active notification details and images are held in memory for the island. **Notification history is off by default.** If enabled, it stores notification titles, text, app/package identifiers, timestamps, categories and action labels in a private on-device SQLite database. The retention setting controls expiry; cleanup depends on the app running, so it is not a guaranteed deletion deadline while the process is stopped. Disabling history capture does not immediately erase existing entries. Use the history controls to delete entries or clear all history.

Settings use AndroidX DataStore Preferences. Weather location/cache and other feature state use private app storage. A custom weather API key is stored locally; it is omitted from the app's settings JSON export. Settings and cached data remain until you remove them, clear app storage or uninstall.

Optional diagnostic recording keeps a bounded in-app log and a private log file across process restarts. Crash traces can be recorded even when optional recording is off. Exported diagnostic reports include device information, permission status, settings, recorded events and the app's process logcat. Identifiers, selected apps and event metadata can be sensitive: inspect a report before saving it to a shared/cloud destination, copying it or sending it to another person. Exports happen only when you choose an export/share action. Pausing recording does not delete earlier logs; use **Clear** to remove the app's saved log buffer. Android controls the lifetime of its separate logcat buffer.

The current backup rules exclude notification databases, SharedPreferences (including location caches), DataStore settings and diagnostic files from Android cloud backup and device-to-device transfer. Other app files, such as imported fonts, may still be backed up. The app cannot retract data already included in an older backup or a report you previously exported. Android/OEM backup behavior and a destination you select for manual export also depend on that system or service.

## Shizuku And Sensitive Notifications

Shizuku is optional and grants shell-level capabilities after you authorize it. Setup can enable accessibility/notification access, battery/background exemptions and sensitive-notification access that bypasses Android's usual OTP redaction. The all-permissions setup also requests optional location, usage and Do Not Disturb access. Picture fallbacks can read notification media through Shizuku and search an app's media folders. Face-unlock features can inspect system log events. Review these capabilities before authorizing Shizuku; they are not needed for every feature.

## Your Controls

You can:

- Turn off Smart Island in the app.
- Revoke overlay permission in Android settings.
- Revoke notification listener access in Android settings.
- Revoke accessibility, location, calendar, usage, Bluetooth or Shizuku access in their respective system/app settings. Features depending on a revoked grant may stop working.
- Turn off network access, disable history capture, clear saved history and clear recorded diagnostic logs separately.
- Clear app storage or uninstall the app to remove local settings.

## Changes

Changes to data access, network recipients, storage or sharing should be reflected in this policy before release.

## Contact

For privacy questions, open an issue in this repository without including private notification content.
