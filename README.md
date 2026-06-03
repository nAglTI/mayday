# mayday Android

[Русская версия](README.ru.md)

mayday is an Android client for a protected connection, focused on simple connection control, clear status visibility, and practical day-to-day settings.

The app is designed for people who need a compact connection client with a modern interface, quick access from the system shade, and control over which apps use the protected connection.

## Features

- Clean Android interface with adaptive light and dark themes.
- First-run onboarding for importing a Mayday key or continuing without a configuration.
- Key-only setup from a pasted key, clipboard content, or a `mayday://import/...` link.
- Home screen focused on connection status, user ID, and clear connect/disconnect controls.
- Compatibility warning when a saved key no longer matches the current app version.
- Automatic GitHub release check with a small update banner when a newer version is available.
- Connection settings for transport mode, MTU, packet handling, network rescue, and probe behavior.
- Popup transport selector that fits long transport names and new transport options.
- Expandable advanced diagnostics for protocol, speed, endpoint, and technical details.
- Split tunneling with app selection.
- Dedicated split tunneling screen for managing selected apps.
- Quick Settings tile for connecting and disconnecting from the Android system shade.
- Foreground notification with connection status and connection actions.
- English and Russian interface text.
- Automatic language selection on first launch based on the system language.
- Persistent local profile and UI settings.

## Configuration Import

mayday now uses a key-first import flow. Users can paste a Mayday key, import it from the clipboard, or open a `mayday://import/...` link.

Raw profile files and manual YAML import are not part of the user-facing flow. If a saved key was created for older app requirements, mayday shows a warning and asks the user to get a new key.

## Screens

- Home: current connection state, user ID, update and compatibility banners, main connection controls, and expandable advanced diagnostics.
- Onboarding: first setup with key import, clipboard import, or skip.
- Settings: app preferences, key import, connection settings, and advanced options.
- Split tunneling: app selection for protected connection usage.

## Android App Structure

The project is organized into a small set of Android layers:

- App layer: application entry point, navigation, and dependency setup.
- Feature layer: user-facing screens and screen state.
- Core layer: shared models, persistence, design system, and Android platform integration.

This README is limited to the Android application experience and high-level project organization.

## Compatibility

- Android 10 or newer.
- APK includes native runtime for arm64-v8a.
- Older keys may need to be renewed when the app shows a compatibility warning.

## Release Notes

Release notes are stored in [docs/release-notes](docs/release-notes).

The current release notes are available here: [mayday 2.1.0](docs/release-notes/2.1.0.md).
