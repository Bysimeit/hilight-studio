# Changelog

All notable changes to HiLight Studio are documented here.

## [Unreleased]

## [1.1.0-experimental]

- Added built-in setup: HiLight Studio now pairs with the phone's own Wireless debugging service
  and starts its renderer itself, so no computer, no Shizuku install, and no typed shell commands
  are needed.
- Pairing is entered from a reply notification, so the Settings pairing dialog can stay open while
  the six-digit code is sent.
- The renderer is re-attached automatically after a reboot or an app update, and whenever the app
  finds it is no longer running.
- Added `INTERNET`, `ACCESS_NETWORK_STATE`, and `ACCESS_LOCAL_NETWORK`, used only to find and reach
  the phone's own debug daemon, and `RECEIVE_BOOT_COMPLETED` for the automatic reconnect. Android 17
  requires the local-network permission at runtime and asks for it on first launch.
- Shizuku and the two-command ADB flow are still available as alternatives.

## [1.0.5-experimental] - 2026-08-22

- Added **Japanese**. Every user-visible string moved out of the code and into resources, and the app
  now declares its languages, so Android's own per-app language picker can show HiLight in Japanese
  while the rest of the phone stays in English.
- Terms are fixed by a glossary rather than translated string by string, so the same English word does
  not become two Japanese ones. Product names (HiLight, Shizuku, ADB, LED) stay in Latin script, and
  Android's own Japanese is followed for the system features HiLight talks about, so a button and the
  Settings screen it opens agree with each other.
- Two things extraction turned up that were bugs in English too: the Quick Settings tile chose its
  accent colour by comparing a *label* to the word "Rainbow", and the catch-all rule stored its own
  name, so a rule created in one language kept that name in the other.
- Added **per-contact rules**: a colour for one person or one chat, so a message from a chosen contact
  lights the array differently from everything else in the same app. Works with WhatsApp, Google
  Messages, Telegram, Signal, Slack, Discord and anything else that names the sender in its
  notification, and needs no permission beyond the notification access the app already asks for.
- Chats are never typed in. HiLight offers the chats it has already seen, the system contact picker,
  or a "learn the next message" mode that captures the name exactly as the app writes it. A rule
  remembers the chat's stable id on first sighting, so renaming a contact no longer breaks it.
- Added a **notification inspector** under Setup, which shows what HiLight reads from each
  notification and can be copied or shared to explain why a rule is not firing. Message text is never
  shown and never exported.
- Added **Forget remembered chats** under Setup, which clears the remembered chat names without
  touching existing rules.
- Rule cards now show when a rule last matched, so a rule that never fires is visibly a rule that
  never matched rather than an array that is broken.

## [1.0.4-experimental] - 2026-08-20

- Released the first APK signed with HiLight Studio's permanent release certificate, establishing
  a stable update identity for future GitHub releases.
- Released the HiLight session as soon as the array goes dark, so system effects such as calls and
  Gemini can resume without waiting for the helper to stop or the phone to reboot.

## [1.0.3-experimental] - 2026-08-20

- Fixed notification alerts that could leave the LEDs lit indefinitely, end early after an
  unrelated settings update, or continue after the phone was unlocked.
- Added a **Pause in Battery Saver** option and changed the default low-battery pause from 20% to
  10%.
- Reset the brightness taper after the array has been dark, so a newly armed effect starts at full
  brightness.
- Made renderer handoff explicit so only one renderer drives the array at a time.
- Changed ADB setup to a two-line reset-then-start flow, with separate commands for PowerShell and
  Windows Command Prompt.

## [1.0.2-experimental] - 2026-08-19

- Corrected the ADB command shown in the app's setup screen.
- Added automated tests for LED duty-cycle, taper, rest, and quiet-hours safety behavior.
- Hardened the release workflow, build verification, and contributor resources.

## [1.0.1-experimental]

- Added the unified HiLight Studio logo across the app and repository.

## [1.0.0-experimental]

- First experimental GitHub release.
