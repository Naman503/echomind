# UX & design research (Oct 2026)

## Platform
- Compose BOM 2026.09.00 (ui 1.12.1, material3 1.4.0). material3 **1.5.0-beta01** carries M3 Expressive (LoadingIndicator, wavy progress, ButtonGroup, FloatingToolbar, MaterialShapes morph, MotionScheme, flexible app bars). `MaterialTheme.motionScheme` (LocalMotionScheme removed in alpha27).
- Navigation3 1.2.0 (user-owned back stack, scenes for list-detail). material3-adaptive 1.3.0 (NavigationSuiteScaffold).
- targetSdk 36: edge-to-edge cannot be opted out; predictive back on by default.
- Glance 1.2.0, Lifecycle 2.11.0, WorkManager 2.12.0, Room 2.8.5, Paging 3.5.3.

## What the category gets wrong (and what we do instead)
| Complaint (Limitless, Bee, Omi, Plaud, Otter) | EchoMind answer |
|---|---|
| TV/media leaks into tasks; hordes of suggested to-dos (Bee) | Review inbox, batch accept, auto-expire 72 h, one-tap dismiss reasons that feed the filter ("Was TV") |
| "Listening" shown while nothing recorded for days (Omi) | Watchdog on real audio frames; gaps shown on the timeline with reason; Attention notification |
| Chat makes things up | Always cite; honest "not found" with closest moments |
| Weak speaker ID | "Me" vs others from enrolled voice; rename/merge speakers |
| Bystander privacy | Pause sheet (15 m/1 h/until tomorrow), "Forget last 5/15 min" with undo |

Borrowed: Granola two-tone text (AI grey, user black; edited AI text turns black) and per-bullet citations; Bee "facts about you"; Day One daily prompt / On This Day; Apple Journal on-device suggestions; Mem inline source annotations; Pixel Recorder M3E look.

## Patterns
- Status pill on every main screen (recording / paused / blocked × on-device / cloud × processing) + ongoing notification + QS tile + widget, all in sync.
- Processing screen from the pill: queue states, whole-day progress, ETA, calm wording, "Process now" / "Only while charging". OS timeout = "Paused by Android", not an error.
- Ask: streaming answer with numbered citation chips → sheet with quote, speaker, time → open transcript at that line. Scope chips (Today, Week, Person). Persistent history; pin answer to Memory/Note.
- Living notes: provenance footnote "Updated 3:40 PM from conversation with Rahul", revision timeline with diffs, AI edits to user-written text arrive as suggestions, lockable sections.
- Tasks: Review inbox → To-do / Reminders / Done. Only items with a time become reminders, and only after accept.
- Diary: AI draft in the evening, user keeps/edits; weekly review with 1–3 "things to improve" cards with evidence.
- Privacy: master mode (Local only / Cloud allowed) + per-feature provider matrix; consent sheet on first cloud use per feature; amber cloud token whenever data leaves; Data ledger of every egress; provenance chip on each AI artifact.
- Onboarding: value → privacy mode → mic (preview notification) → notifications → battery exemption → vivo steps (autostart, high background power, lock in recents) → exact alarms just-in-time → call-recording folder (SAF, optional). Setup-health card on Today until green.

## Notifications
Channels: Recording (LOW, ongoing, Pause/Forget actions) · Processing (LOW, silent; ProgressStyle on 16) · Reminders (HIGH, Done/Snooze) · Suggestions (DEFAULT, batched) · Morning brief · Evening recap · Attention (HIGH: recording stopped, permission revoked, storage full). ≤3 proactive/day outside reminders; never per conversation.

## Visual tokens
Seed indigo `#3D4A9E`; recording coral `#E5484D`/`#FF8A80`; on-device teal `#0F8A7E`/`#5FD4C4`; cloud amber `#B26A00`/`#FFB74D`. Filled tonal cards (surfaceContainerLow, 16 dp radius, no elevation). Type: Google Sans Flex (Latin) + Noto Sans Devanagari fallback, body 16/24 (Devanagari 16/26), tabular numerals for times. Spacing 4-dp grid, gutter 16. Motion: standard scheme; expressive only for hero moments (record morph, Ask reveal).

## Performance
Baseline profiles; R8; strong skipping (default); @Immutable UI models; LazyColumn key + contentType; lambda modifiers for animated values; collectAsStateWithLifecycle; one StateFlow<UiState> per screen; Paging for long transcripts; heavy work off main, progress via Room.

## IA
Today · Timeline · Ask · Tasks · Library (Notes, Memory, Journal, Insights) + Settings (Privacy & providers, Data ledger, Recording, Calls, AI sessions, Notifications, Battery & vivo setup, Storage, Export/delete) + Processing screen.
