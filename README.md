# mayday Android

Android VPN client shell for the `vpncore.aar` Go runtime. The app provides:

- VPN start and stop via `VpnService`
- profile import from YAML or JSON
- editing relay, user ID, DNS, MTU, and `servers[]`
- split tunnel by Android package name

## Requirements

- Android 8.0+ (`minSdk 26`)
- a valid `vpncore.aar` in `app/libs/`
- a valid client config with:
  - `relay`
  - `user_id`
  - at least one server in `servers[]`

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

## How to use

1. Install the debug APK on the device.
2. Open `Settings`.
3. Import a YAML or JSON config, or fill the fields manually.
4. Check relay, `user_id`, and the server list.
5. Optionally configure DNS, MTU, and split tunnel.
6. Tap `Save profile`.
7. Go to `Home` and tap `Start VPN`.
8. Accept the Android VPN permission dialog.
9. Wait until the status changes to `Running`.

To stop the tunnel, tap `Stop VPN` on the home screen. The app waits for `vpncore` to close the active TUN before the foreground notification is removed.

## Config format

Minimal JSON example:

```json
{
  "relay": "relay.example.com:51821",
  "user_id": 1,
  "dns": "1.1.1.1",
  "servers": [
    {
      "id": "exit-1",
      "key": "64-char-hex-key",
      "priority": 1
    }
  ]
}
```

Minimal YAML example:

```yaml
relay: relay.example.com:51821
user_id: 1
dns: 1.1.1.1
servers:
  - id: exit-1
    key: 64-char-hex-key
    priority: 1
```

Optional `split_tunnel` fields are also supported:

```yaml
split_tunnel:
  enabled: true
  mode: whitelist
  apps_android:
    - org.mozilla.firefox
    - com.android.chrome
```

## Short algorithm note

The Android app is only the shell. The VPN algorithm itself runs inside `vpncore.aar`.

High-level flow:

1. Android creates a placeholder TUN with `VpnService.Builder`.
2. The Go core opens a protected control connection to the relay, so relay traffic does not loop back into the VPN.
3. The relay and exit side establish the client session and the exit assigns the client tunnel IP.
4. Android receives `AssignedIP`, creates a new TUN with the real address, and hot-swaps the TUN fd into the running Go core.
5. App traffic from the allowed Android packages enters the TUN, is processed by the Go core, sent through relay and exit, and returned back into the device.

Security-relevant properties at this level:

- tunnel traffic is handled by the Go core, not by UI code
- relay sockets are explicitly protected from re-entering the VPN
- split tunnel is enforced by Android package routing rules, not by app-level proxying
- the client keeps one relay session alive during TUN hot-swap instead of reconnecting the whole session

This file intentionally stays high-level and does not document low-level protocol internals.

## Troubleshooting

- If `vpncore.aar` is missing or incompatible, the app reports that the core could not be initialized.
- If import fails, verify that the config contains `relay`, `user_id`, and at least one server in `servers[]`.
- If VPN startup succeeds but there is no traffic, check the imported relay and server values first, then inspect `logcat` for `VpnCoreService` and `AarBackedVpnCoreBridge`.
- If stop remains in `Stopping` and the Android VPN key does not disappear, the client is waiting for `vpncore.stop()` to close the active TUN. In that case the next thing to inspect is the AAR runtime.
