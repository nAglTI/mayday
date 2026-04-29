# mayday Android

[Русская версия](README.ru.md)

mayday is an Android client for a protected connection, focused on simple connection control, clear status visibility, and practical day-to-day settings.

The app is designed for people who need a compact connection client with a modern interface, quick access from the system shade, and control over which apps use the protected connection.

## Features

- Clean Android interface with a dark visual style.
- First-run onboarding for initial setup.
- Home screen with connection status, profile summary, and connect/disconnect controls.
- Connection profile management.
- Profile import from a shared file, pasted text, or clipboard content.
- Split tunneling with app selection.
- Dedicated split tunneling screen for managing selected apps.
- Quick Settings tile for connecting and disconnecting from the Android system shade.
- Foreground notification with connection status and connection actions.
- English and Russian interface text.
- Automatic language selection on first launch based on the system language.
- Persistent local profile and UI settings.

## Screens

- Home: current connection state, selected profile summary, and main connection controls.
- Onboarding: guided first setup.
- Settings: profile and app preferences.
- Split tunneling: app selection for protected connection usage.

## Android App Structure

The project is organized into a small set of Android layers:

- App layer: application entry point, navigation, and dependency setup.
- Feature layer: user-facing screens and screen state.
- Core layer: shared models, persistence, design system, and Android platform integration.

This README is limited to the Android application experience and high-level project organization.

## Compatibility

- Android 10 or newer.
- Optimized for modern Android notification and Quick Settings behavior.

## Release Notes

Release notes are stored in [docs/release-notes](docs/release-notes).

The current release notes are available here: [mayday 1.0.0](docs/release-notes/1.0.0.md).
