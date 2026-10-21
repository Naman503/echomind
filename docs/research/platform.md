# Android platform research (Android 15 / Funtouch 15, targetSdk 36)

## Microphone foreground service
- Needs FOREGROUND_SERVICE_MICROPHONE + RECORD_AUDIO; `startForeground(..., TYPE_MICROPHONE)` within ~10 s.
- **Cannot be created from the background** (SecurityException). Allowed starts: activity visible, notification interaction, widget interaction, PendingIntent from another visible app, system. NOT allowed: BOOT_COMPLETED, MY_PACKAGE_REPLACED, exact alarms, FCM, battery-exempt. QS tile → use `startActivityAndCollapse(PendingIntent)` to a trampoline activity.
- Microphone type has no time cap. dataSync/mediaProcessing: 6 h / 24 h in background, `onTimeout` → stopSelf within seconds.
- Keep one service alive: pause = release AudioRecord inside the running service, never stop the service. Resume-after-pause from a Handler in the live service.
- Process death → START_STICKY restart is a background start → mic FGS throws. Catch, post "Capture stopped — tap to resume" (PendingIntent.getForegroundService), stopSelf.
- Boot/update receivers only post "Tap to resume".
- Diagnose kills with `ActivityManager.getHistoricalProcessExitReasons()`; watchdog = periodic WorkManager checks heartbeat.
- **Don't hold our own wakelock while capturing** — audioserver keeps the CPU awake for AudioRecord; Play vitals exempts audio wakelocks. Hold one only while processing.

## Concurrent capture
- During cellular/VoIP calls (MODE_IN_CALL / IN_COMMUNICATION) and when a visible app records, our AudioRecord keeps running but gets **silence**.
- Detect: `AudioRecord.registerAudioRecordingCallback` (before startRecording) → `isClientSilenced()`; `AudioManager.addOnModeChangedListener` (API 31, no permission).
- Use `VOICE_RECOGNITION` source and `setPrivacySensitive(false)` so we never steal the mic from the user's own voice notes.

## Call recordings (vivo)
- Verified on this phone: `Recordings/Record/Call/` is indexed in MediaStore.Audio with `is_recording=1`; ~6,500 files, names like `Dipika Pathak 2026-10-20 22-10-16.m4a`, `(⛔ Spam Alert From Jio) Name  2026-03-22 16-19-15.m4a`.
- Permission: READ_MEDIA_AUDIO (Play-safe). Avoid MANAGE_EXTERNAL_STORAGE. SAF tree as fallback.
- Watch: ContentObserver on MediaStore.Audio while the service runs; WorkManager content-URI trigger as backup; idempotent by MediaStore generation/_ID.
- Decode AAC with MediaExtractor + MediaCodec, downmix, resample to 16 kHz.

## Background processing
- Run heavy work inside the running mic FGS (process already foreground-priority). On stop, switch to `mediaProcessing` for backlog (6 h cap, onTimeout).
- Gate on charging, battery temperature < ~38 °C, `getThermalHeadroom(30)` < 0.7 (call ≤ every 10 s), thermal status listener. ADPF `PerformanceHintManager` + `setPreferPowerEfficiency(true)` (API 35); limit threads.
- WorkManager fallback: requiresCharging + deviceIdle + batteryNotLow; chunks < 10 min. Charging lifts bucket limits.

## vivo / Funtouch
No developer-side fix (dontkillmyapp). User steps: Battery → High background power consumption → enable; App info → Battery → Unrestricted; Autostart; lock in Recents. Candidate intents (try + fallback to app details): `com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity`, `com.vivo.permissionmanager/.activity.BgStartUpManagerActivity`, `com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager`.

## Notifications & surfaces
Channels: capture (LOW, silent), attention (HIGH), reminders (HIGH), processing (LOW), digest (DEFAULT). Ongoing actions via `PendingIntent.getForegroundService` (counts as notification interaction). Exact alarms: sideloaded → USE_EXACT_ALARM; else SCHEDULE_EXACT_ALARM + canScheduleExactAlarms(). Full-screen intents not for us. Live Updates not appropriate for an always-on indicator. QS tile (trampoline), Glance widget (`actionStartService(isForegroundService=true)` is a while-in-use exemption), static shortcuts, share target, ACTION_ASSIST activity.

## Bluetooth mic
SCO / LE audio via `setCommunicationDevice` + VOICE_COMMUNICATION; puts the phone in communication mode (music degrades, earbud battery drains). Opt-in only; offer on connect.

## Battery
Expect ~1.5–4 %/h for continuous capture; measure with `dumpsys batterystats`. Big buffers, one blocking read thread, batch writes, no per-frame allocation/logging.
