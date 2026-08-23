# Changelog

All notable changes to HiLight Studio are documented here.

## [Unreleased]

- The renderer now comes back by itself when Wireless debugging is switched back on. HiLight watches
  the switch instead of waiting for the app to be opened, and clears its retry backoff when the
  daemon returns, so the LEDs are back within a reconnect rather than within minutes.
- Added **Start on its own after a reboot**, off by default, under Built-in access. With it on,
  HiLight turns Wireless debugging on at boot just long enough to start a renderer and then turns it
  back off, so a restart needs no trip through Developer options. It needs USB debugging on — with
  no other transport, switching Wireless debugging back off would stop the daemon and kill the
  renderer that was just started — and it restores the switch even when the attempt fails, so a
  debug port is never left open behind it. This is what `WRITE_SECURE_SETTINGS` in the manifest is
  for; it is granted by the renderer during setup and does nothing on a phone that never ran one.
- Setup and the README now explain what actually has to stay on after setup, and it is not Wireless
  debugging: it is the phone's debug daemon. The renderer is started by that daemon and Android
  stops a daemon by killing everything it started, so **Wireless debugging can be switched off once
  USB debugging is on** — a developer-options toggle, needing no cable and no computer, verified
  with the cable unplugged. Turning both off is what takes the array with them. This is also the
  quieter phone to leave behind, since Wireless debugging keeps a debug port listening on the local
  network. Measured and written up in
  [docs/TECHNICAL.md](docs/TECHNICAL.md#why-the-renderer-cannot-outlive-the-debug-daemon).
- The copyable ADB commands and `scripts/start-helper.sh` now detach with `setsid` and `< /dev/null`,
  matching what the app itself runs, so a renderer started from a computer survives the disconnect.
- **Built-in access is translated.** It was the last screen whose strings were literals in the code,
  so it showed in English whatever the phone's language; its 44 strings are now resources in English,
  French and Japanese, including the connection-state pill. Both glossaries record the terms that
  were settled — *association* rather than *appairage* for the pairing step, as Android's own French
  names it, and ペア設定 to match the Wireless debugging screen.

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
