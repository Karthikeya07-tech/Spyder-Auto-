# Spyder

Android companion for the Twist-auto quadruped. The phone joins the robot’s Wi-Fi, draws the live LiDAR map and pose, and queues stand, walk, and turn clips on the ESP32-P4.

Firmware is [Twist-auto](https://github.com/Karthikeya07-tech/spyder). This app does not plan a path and does not talk to the servos directly. It calls the robot’s HTTP and WebSocket API.

There is no Bluetooth and no USB path in this project.

## Requirements

- Android 10 or newer (minSdk 29, targetSdk 36)
- Android Studio, or the Gradle wrapper in this repo (Gradle 8.13)
- A phone that can join a Wi-Fi access point with no internet
- The robot running Twist-auto, advertising `Twist-auto`

Package name is `com.example.spyder`. The UI is Kotlin and Jetpack Compose, one activity, one screen.

## Build

```text
gradlew.bat :app:assembleDebug
gradlew.bat :app:installDebug
```

Use a physical phone. The robot access point has no internet, so an emulator will not complete the link the way a handset does.

## Connect

| | |
|---|---|
| SSID | `Twist-auto` |
| Password | `twistmap1` |
| Robot | `http://192.168.4.1` |
| Live socket | `ws://192.168.4.1/ws/live` |

Open the app and tap connect, or join `Twist-auto` from system Wi-Fi first. The app probes `GET /api/status` and, once that answers, keeps HTTP and the WebSocket on the robot network so traffic does not leave over mobile data.

The live feed is the WebSocket. The app sends `g` about five times a second. If the socket does not open, it polls `GET /api/live.json`. When `map_version` changes it downloads `GET /api/map.bin`.

## On the live screen

Hold still, tap **Calibrate front**, then **Start scan**. The LiDAR motor stays off until Start scan.

| Control | Robot call |
|---|---|
| Calibrate front | `POST /api/imu/calibrate` |
| Zero arrow | `POST /api/imu/zero` |
| Reset map | `POST /api/map/reset` |
| Start scan / Stop | `POST /api/scan/start` and `/api/scan/stop` |
| Forward, Back, Left, Right, Neutral | `POST /api/action/walk_forward`, `walk_backward`, `turn_left`, `turn_right`, `neutral` |

Stop on this screen stops the LiDAR. It does not cancel a gait. One clip plays and two can wait. The line under the buttons shows what is playing. A full queue leaves the motion buttons disabled.

The map is the robot frame drawn as a plot: +X to the right, +Y up. White is free space, near-black is a wall, grey is unknown, orange dots are the current sweep. The blue arrow is the live pose. The red A is world (0, 0), not a goal you picked. Pinch, pan, and Recenter move the view. The screen stays on while the link is live.

The telemetry line is `x`, `y`, hit count, and map version, from the pose the firmware reports.

## Not in the app yet

Set A, tap to set B, Plan, and Go. Those wait on firmware routes that are not implemented (`/api/nav/goal`, `/api/nav/plan`, `/api/nav/start`, saved-map download). The motion buttons send a whole clip. They do not send `?cycles=1`.

## Progress note

[Spyder_Current_Progress.docx](Spyder_Current_Progress.docx) is the shared status of firmware and this app, dated 25 November 2026. The firmware repo regenerates it with `python tools/write_progress_doc.py`.
