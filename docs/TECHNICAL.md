# Technical deep dive

This is the implementation detail that doesn't belong in the main [README](../README.md): how
the renderer gets privileged access, what the hardware actually is, and what's been verified on a
real device.

## What HiLight actually is

Findings from the device itself, not from the marketing pages:

| Property | Value |
|---|---|
| Hardware | **8 individually addressable RGB LEDs** in the array around the camera flash |
| Framework type | `Light.LIGHT_TYPE_APPLICATION` (`10`), new in API 37 |
| Light ids / ordinals | ids `1..8`, ordinals `0..7` (id `0` is the display backlight and is not exposed by `LightsManager`) |
| Capabilities | `hasRgbControl() = true`, `hasBrightnessControl() = false`, `hasAnimationControl() = true` |
| Min update period | `33 ms` per LED, i.e. ~30 fps |
| HAL | `android.hardware.light` **AIDL version 3** (`vendor.google.lights-service`), AOSP ships v2 |
| System feature name | `AmbientCue`: `/product/overlay/AmbientCueOverlay.apk`, plus `vendor.google.ambience_hub.*` HAL services |
| Stock features | custom colour per favourite contact (Phone by Google, WhatsApp) and a Gemini listening/thinking/responding indicator |

New public API in Android 17 (API 37), all in `android.hardware.lights`:

- `ColorSequence` + `ColorSequence.Builder`: keyframed colour ramps (`addControlPoint(delayMs, color)`,
  `INTERPOLATION_MODE_NONE` / `INTERPOLATION_MODE_LINEAR`)
- `MultiLightEffect` + `Builder`: one `ColorSequence` per LED, with `setIterations()` and `setPreemptive()`
- `LightsRequest.Builder.setEffect(...)`, `Light.hasAnimationControl()`, `Light.getMinUpdatePeriodMillis()`

Underlying binder interface (`ILightsManager`): `getLights()`, `openSession(IBinder, int priority)`,
`setLightStates(token, int[] ids, LightState[])`, `setLightEffect(token, MultiLightEffect)`,
`getLightState(id)`, `getLightSequence(id)`, `closeSession(token)`.

### Why a privileged helper process is required

`android.permission.CONTROL_DEVICE_LIGHTS` is `signature|privileged` on this build, and
`LightsService` enforces it on every call:

```
java.lang.SecurityException: Access denied, requires: android.permission.CONTROL_DEVICE_LIGHTS
  at android.hardware.lights.ILightsManager$Stub.getLights_enforcePermission
```

It is not a changeable permission, so `pm grant` refuses it, and the device is a retail unit with a
locked bootloader (`ro.boot.flash.locked=1`, `verifiedbootstate=green`, no root), so there is no way to
install an app as privileged.

However `android.uid.shell` (uid 2000) **already holds it** (`granted=true`). So the rendering runs in
a process owned by the shell UID. Everything else, including the UI, rules, and notification listener, is a normal
app.

## Architecture

The renderer core (`core/src`) is shared. It can run as root, or as the shell UID through Shizuku or
ADB.

```
HiLight Studio (normal app)                    privileged renderer (uid 0 or 2000)
┌─────────────────────────────────┐            ┌────────────────────────────────────┐
│ Compose UI: Live/Ambient/Apps   │  binder    │ Shizuku: HiLightUserService        │
│ NotificationTrigger (listener)  │ ─────────► │   com.hilight.studio:hilight       │
│ ForegroundWatcher (UsageStats)  │            ├────────────────────────────────────┤
│ Store: layering + rules         │  2 JSON    │ ADB: com.hilight.core.AdbHelper    │
│ Transport: Auto/Root/Shizuku/ADB│ ◄────────► │   run from the installed APK       │
│ AdbAccess: own ADB client       │  files     │   started by the app, root, or adb │
└─────────────────────────────────┘            └────────────────────────────────────┘
                                                shared core: Engine + Renderer + LightsBackend
```

The normal app has one manual network path: tapping **Check for updates** fetches the public release
list from GitHub. It does not run in the background and does not send rule, notification, renderer,
or device state.

**Built-in access (default without root).** The app is its own ADB client. It discovers the phone's
debug daemon over mDNS, pairs once with the six-digit code from Wireless debugging, opens a TLS shell
session to `127.0.0.1`, and runs the same two commands the manual flow used to ask for. From there it
is the ADB transport: the helper polls `state.json` and writes `helper_status.json`. Discovery,
pairing, and reconnection live in `AdbAccess`, `AdbPairingService`, and `AdbReconnect`.

The pairing service (`_adb-tls-pairing._tcp`) is advertised only while the Settings pairing dialog
is open, and the code is shown in that same dialog, so the code is collected from a notification
with a `RemoteInput` reply action rather than from a field inside the app. The RSA key and its
self-signed certificate are generated once and kept in app-private storage, so the daemon keeps
trusting the app across reboots and updates.

**Root transport (automatic when available).** The app checks for `su` without elevating. When
HiLight is turned on, it asks the root manager once, resets old renderers, and launches `AdbHelper` as
uid 0. The app accepts the new renderer only after its PID, owner, state revision, and idle privacy
state all match. A denied or failed request leaves output off and exposes the Shizuku/ADB fallbacks.

**Shizuku transport (no computer).** Shizuku launches `HiLightUserService` into a shell-UID
process (`daemon(true)`, so it outlives the UI) and the app holds a real binder to it. State is
pushed straight in, no polling. Verified running as `shell` uid 2000.

**ADB transport (fallback).** `AdbHelper` ships inside the APK, so the start command launches it with
no file to push. Cross-UID binder is not usable there: a shell-UID process that touches a
`ContentProvider` is killed by ActivityManager (verified), which rules out both a provider bridge and
`ContentObserver` push. So that transport exchanges two small JSON files instead.

**File ownership rule that matters** for the ADB transport: on external storage a file keeps the UID
of whoever created it. A file created by the shell is unreadable by the app, but the shell *can* write
into a file the app owns. So the app creates the directory and both files, and the helper only ever
overwrites in place.

Only one renderer may drive the array at a time. A transport change first sends an idle state to the
old renderer and waits for the matching revision and a stopped privacy observer. Only then is current
state sent to the replacement. A two-second timeout fails closed: the replacement stays dark instead
of risking two owners.

Output layering, highest first:

1. a finite notification alert
2. an active microphone or camera privacy rule
3. an infinite "while this app is open" override
4. the always-on ambient look

During a privacy-rule cooldown the renderer blanks the LEDs and closes its light session instead of
falling through to a lower layer. Turning control off does the same, handing HiLight back to Android.

## The device illustration

The Live tab draws the phone's own back with HiLight lit by the same pattern maths the hardware runs.
It is a vector reconstruction, not a bundled press image: Google's product renders are copyrighted, so
shipping them in an app is not an option, and a drawing can be animated by the live frame data anyway.

It follows `Build.MODEL`:

| Model | Layout |
|---|---|
| Pixel 11 Pro / Pro XL | full-width camera bar, three lenses, HiLight at the right-hand end |
| Pixel 11 Pro Fold | unfolded rear panel with the hinge seam, compact camera block top-left, HiLight inside it |
| Pixel 11 (non-Pro) | camera bar with a plain flash, and the card says HiLight is Pro-only |
| anything else | generic Pro-style layout |

The framing is a close crop on the camera bar. Only the top of the device is shown, running off the
bottom of the card, which is how Google frames the feature in its own material.

The array is drawn as one diffused disc rather than eight pinpoints, because the eight LEDs sit behind
a single flash window. Each LED still contributes its own colour from its position inside the window,
clipped to the window so the light keeps a crisp edge, so a chase or a rainbow visibly travels around
the lamp.

## Verified on device

- 8 LEDs enumerated with the capabilities in the table above
- solid, per-LED rainbow, comet, wave, breathe, pulse and random rendering on the real hardware
- alert layer expiring back to ambient, and an infinite override being cleared
- UI → hardware: picking Solid violet at 70% produced `ff5635b2` on all 8 LEDs
- notification path: a notification from a rule's package produced a green pulse within one frame
- foreground path: opening Chrome produced solid `ff2979ff`, returning home restored ambient
- privacy path: the v1.0.6 standalone helper observed Pixel Camera through AppOps, entered the camera
  rule's lit phase with a live LED session, then returned to inactive and closed the session when the
  camera process stopped
- animation keeps running with the screen off (`mState=DOZE`), including the face-down case
- turning control off closes the session and blanks the array
- Shizuku transport: user service starts as `shell` uid 2000 with 8 LEDs, binder connects, ambient and
  notification alerts render with no adb helper running at all
- ADB reset and start commands launch the renderer straight out of the installed APK
- failover: killing the Shizuku server mid-animation is detected, state is re-pushed, and the ADB
  helper picks the array up, with no overlap between sessions
- Shizuku 13.6.0 (official release, signer `CN=Rikka`) used for all of the above

### Why the renderer cannot outlive the debug daemon

The renderer is detached as thoroughly as a shell-UID process can be: `setsid` for its own session,
`nohup`, `< /dev/null`, and both output streams redirected to a file. Measured on a Pixel 11 Pro
(Android 17, API 37) after starting it the way the app does:

```
renderer  pid=32371  ppid=1  pgid=32371  sid=32371
```

Reparented to init, its own session, its own process group. Nothing about the shell that started it
can reach it any more. And yet:

```
renderer  0::/system/uid_0/pid_31692
adbd      0::/system/uid_0/pid_31692     ← 31692 is adbd
```

It is inside **adbd's cgroup**, and its pid is listed in that cgroup's `cgroup.procs`. Android's init
does not stop a service by signalling one pid; it calls `killProcessGroup()`, which SIGKILLs every
process the cgroup lists. Sessions and process groups are not consulted, and SIGKILL cannot be
ignored. So when the last debug transport goes away and init stops adbd, the renderer goes with it.

There is no way out of that cgroup from uid 2000 on a locked retail device. Every candidate was
tried on the device and refused:

| Attempt | Result |
|---|---|
| `echo $$ > /sys/fs/cgroup/cgroup.procs` | `Permission denied` — the file is `system:system 0775`, and shell is not in `system` |
| `/sys/fs/cgroup/uid_2000/` | does not exist |
| `mkdir /sys/fs/cgroup/hilight` | `Permission denied` |
| `setprop ctl.restart adbd` | refused for shell |

So what the renderer actually depends on is **adbd staying alive** — not on Wireless debugging in
particular. adbd runs while any debug transport is enabled, and it does not care which:

| Wireless debugging | USB debugging | Cable | adbd | Renderer |
|---|---|---|---|---|
| on | either | either | running | alive |
| off | **on** | plugged | running — pid unchanged across the toggle | alive |
| off | **on** | **unplugged** | running | **alive** |
| off | off | either | stopped by init | killed with the cgroup |

The last row is the reported symptom; the two middle rows are the way out of it. **Wireless debugging
can be switched off after setup, as long as USB debugging is enabled** — and USB debugging is a
developer-options toggle, so this needs no cable and no computer. All four rows were measured on a
Pixel 11 Pro running Android 17, including the unplug: the renderer kept driving the array with the
cable out and was still on the same pid when it came back.

What stops adbd is the setting going off, not the cable coming out. Confirmed from the other side as
well: after both switches were turned off and USB debugging turned back on, adbd returned on a
different pid (31692 → 10839) — the restart that kills the renderer.

That end state is also the better one to leave a phone in. Wireless debugging keeps a TLS debug port
listening on the local network; USB debugging with nothing plugged in exposes nothing. Wireless
debugging is still what *starts* a renderer, because it is the only transport the app can reach on
its own, so it is turned back on after a reboot and can go off again once the array is running.

Two consequences worth writing down, because both look like bugs from outside:

- **A persistent app ↔ renderer channel would not change this.** The ADB transport already survives
  adbd perfectly well — it is two JSON files on shared storage, and nothing about it touches the
  daemon once the renderer is up. Replacing it with a binder handoff would make the channel faster
  and give the app instant death detection, but the renderer would still be killed at exactly the
  same moment, because what kills it is the cgroup, not the channel.
- **Auto-enabling Wireless debugging at boot cannot then turn it back off.** Granting the app
  `WRITE_SECURE_SETTINGS` and flipping `adb_wifi_enabled` on at `BOOT_COMPLETED` would start a
  renderer, but setting it back to 0 afterwards stops the daemon again and kills the renderer that
  was just started. The switch has to stay on for as long as the array is wanted.

What is done instead is to make the loss self-healing, in two places:

- `AdbAccess.watchWirelessDebugging` observes `adb_wifi_enabled` and starts a fresh renderer the
  moment the switch comes back on, without waiting for the user to open the app.
- **Start on its own after a reboot**, an opt-in in Setup that is off by default. With it on,
  `AdbReconnectService` turns Wireless debugging on itself at `BOOT_COMPLETED`, starts a renderer,
  and turns it back off — a few seconds instead of a manual trip through Developer options.

The second needs `WRITE_SECURE_SETTINGS`, which is `signature|privileged|development|installer|role`
— the `development` flag is what makes `pm grant` legal, unlike `CONTROL_DEVICE_LIGHTS`
(`signature|privileged`, `flags=0x0`), which `pm grant` refuses as "not a changeable permission
type". The start command already runs as shell, so it grants the permission itself on every renderer
start; declaring it in the manifest grants nothing on its own, so on a phone that never completed
setup it stays inert.

Two guards, both of which come straight from the measurements above:

- The auto-start only runs when **USB debugging is on**. Without it, turning Wireless debugging back
  off at the end would stop the daemon and kill the renderer that had just been started — the step
  would undo itself.
- Restoring the switch is in a `finally`, so a failed or timed-out attempt still puts
  `adb_wifi_enabled` back to 0 rather than leaving a debug port open. The one deliberate exception is
  USB debugging disappearing mid-attempt, where leaving it on is the lesser evil; that is logged.

## LED safety implementation

The safety guards summarised in the README live in `Engine`, not in the UI, so no state document can
opt out of them:

| Guard | Default | Ceiling |
|---|---|---|
| Ambient auto-off | 30 s | 5 min, behind two warnings |
| Per-app notification | 10 s | 1 min, behind two warnings |
| Alert hard clamp | Not configurable | 60 s, whatever the app asks for |
| Open-ended holds ("while open") | Not configurable | capped at the auto-off value |
| Duty cycle | Not configurable | at most 50% of any 10-minute window |
| Sustained brightness | Not configurable | eases to 55% after 10 s of unbroken light |

Two details that matter:

- **Only deliberate user action restarts the auto-off window.** A notification firing, a foreground
  override, or the app being backgrounded all push state with `arm: false`, so the array cannot be
  kept lit indefinitely in 30-second increments.
- **Leaving the app kills a running test.** `onStop` clears the preview immediately and does not hand
  ambient a fresh window on the way out.

Verified on device: brightness taper visible as `ff4d50 → 8c2a2c`; auto-off blanking at exactly 30 s;
duty guard tripping after 10 032 ms lit in a (temporarily shortened) 20 s window, resting, then
resuming when the window rolled over; a notification playing without extending the ambient window; and
a test stopping the moment the app went to the background.

What still cannot be measured here: actual power draw and LED junction temperature. Android does not
attribute either per-LED, so these figures are conservative by design rather than tuned to data.

## Per-contact rules

A rule can be scoped to one chat, so a message from one person lights a colour of their own. Nothing
about this needs a new permission: the sender's name is inside the notification the listener already
receives.

`NotificationPeek.read` turns a `StatusBarNotification` into a `MessageInfo`, and
`ConversationMatch` decides which rule that notification belongs to. The matcher is a ladder, tried
strongest first:

| Rung | Source | Survives a rename? |
|---|---|---|
| `KEY` | `Notification.shortcutId`, the app's own stable per-chat id | Yes |
| `NAME` | MessagingStyle sender, group title, or the notification title, normalised | No |
| `CONTAINS` | the rule's name inside the notification title, for apps that pack extra text in — Discord's `Sujay (#general, Server)` | No |

Two keys present and unequal means a different chat, so a key mismatch beats any name similarity.
Names are compared with case, punctuation and emoji stripped, because WhatsApp shows exactly what is
in the address book and a contact saved as `Sujay (work)` would otherwise never match. A rule created
from a name records the chat's `shortcutId` the first time it matches, after which renaming cannot
break it.

Resolution is most-specific-first: a conversation rule for the app, then a conversation rule on the
"any app" sentinel (the same person across WhatsApp and SMS), then the app's plain rule, then the
catch-all.

Things learned from the framework rather than assumed, both of which would have shipped bugs:

- `NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification` returns a
  `conversationTitle` for **one-to-one** chats too, because androidx writes the title into a hidden
  extra unconditionally and restores from it when the visible `EXTRA_CONVERSATION_TITLE` is absent.
  Reading it directly would mark ordinary chats as groups, and a person rule is refused inside a group
  unless it opted in — so every per-contact rule would have stayed dark. The group question is settled
  from `EXTRA_IS_GROUP_CONVERSATION`, or the visible extra, and nothing else.
- Messaging apps re-post the *same* notification on every change to the conversation, so a chat is
  ignored unless it carries a newer message stamp than the last one handled for that notification key.
  Group summaries (`FLAG_GROUP_SUMMARY`) are dropped outright, or a bundled app would flash twice and
  the summary's text would match a rule naming any one member.

Per-app coverage is uneven, and honestly so: WhatsApp, Google Messages and Telegram give a
`shortcutId` and a named `Person`; Slack gives MessagingStyle on recent versions and a title on older
ones; Discord gives neither, so only the title path works; and Signal with message content hidden
gives no sender at all, which no amount of code can recover. The **notification inspector** under
Setup exists for exactly this — it shows what was extracted from each notification, and can be copied
or shared without ever including message text.

## Privacy activity rules

Microphone and camera rules are separate from notification and foreground rules. A rule can target
one package or any app. The privileged renderer observes Android's active AppOps snapshot; it never
opens the microphone or camera and never receives their content.

Callbacks are treated only as invalidation signals. After each callback the watcher reads a fresh,
authoritative snapshot, so duplicate callbacks and process death cannot leave a reference count
stuck. Package names are used only in memory to match the user's rules and are not written to logs or
status files.

Each continuous use gets one monotonic one-minute episode. The default cycle is 10 seconds lit and 10
seconds released. If use stops after five seconds, output stops after five seconds. Overlapping apps
share the same episode for an any-app rule, so switching recorders cannot restart the one-minute cap.
Camera wins over microphone only when an eligible camera rule exists; an unconfigured camera cannot
silence a configured microphone rule.

## Known limits

- The renderer has to be restarted after every reboot. Rooted phones do this automatically when the
  app opens after the root manager has approved it. Without root, built-in access does it itself from
  a `BOOT_COMPLETED` receiver, retrying over a three-minute window because Wi-Fi and the debug daemon
  are usually not up yet at boot; the Shizuku and manual ADB routes still need the user.
- Without root, a debug transport has to stay enabled — Wireless *or* USB debugging, since what the
  renderer depends on is adbd continuing to run; see below. Removing that requirement entirely needs
  root or an unlocked bootloader (app in `/system/priv-app`).
- Built-in access needs the phone to be on a Wi-Fi network: the debug daemon advertises itself over
  mDNS on that interface, and there is nothing to discover without it.
- Root startup is covered by deterministic host tests but is not maintainer-device verified because
  the maintainer's Pixel is intentionally unrooted. Root support is best effort across `su -c`
  compatible root managers; community device reports are welcome.
- If Shizuku is (re)started while HiLight Studio is already running, reopen the app so Shizuku can hand
  it access. Shizuku's own "Authorized applications" count also resets when its server restarts, so it
  may ask for approval again.
- While our session is open the system's own HiLight effects (calls, Gemini) are suppressed, so the
  session is held only while there is actually something to show. The moment the array goes dark,
  whether from the auto-off deadline passing, an alert ending, or the master switch going off, it is handed straight
  back, and a rule firing reclaims it before the first frame. An all-black session left open beats
  the system's own effects, and because the Shizuku renderer is a daemon that outlives the app, that
  used to leave calls and Gemini dark until the phone was rebooted. The Setup tab still exposes the
  session **priority** for the overlap while a look is genuinely running; the exact arbitration rule
  in `LightsService` was not reverse-engineered.
- Deep sleep suspends the CPU, so animations freeze at the last frame until the device wakes. Static
  colours are unaffected.
- The frame period is derived from the slowest light's `getMinUpdatePeriodMillis()` plus a small
  margin (33 ms + 5 ms on the launch devices), and the loop schedules at a fixed rate rather than
  sleeping a fixed delay after each frame. Pushing at or below the advertised minimum lets the
  per-light rate limiter drop updates unevenly across the eight lights, which reads as the array
  animating out of step.
- The array is blanked over several frames before the session is handed back, not one. The lights
  are written in sequence and each rate-limits independently, so a single black frame can be
  dropped for one light, which then stays lit at the last colour of the alert once the renderer
  has released the array.
- The call rules follow the audio mode, not telephony call state, so they need no permission and
  cover VoIP as well as cellular. `MODE_RINGTONE` drives the incoming-call rule and
  `MODE_IN_CALL` / `MODE_IN_COMMUNICATION` the connected one, so answering swaps one look for the
  other without going dark. They hold for the length of the call state and outrank a "while open"
  rule, but yield to a notification flash. Incoming VoIP calls usually ring through a notification
  rather than the ringtone mode, so those light through the app's own notification rule.
- The phone has its own HiLight animation for calls, and it is *not* fully suppressed by holding a
  session: at equal priority the system's frames and ours interleave, which reads as two animations
  fighting over the array. Raising the session priority above the system's settles it; the
  alternative is turning the phone's own HiLight off in Settings. Confirmed on a Pixel 11 Pro —
  with the system feature disabled, our call rules render cleanly.
- A call rule set to hold for the whole call keeps the array lit for the call's full length, which
  a long call will spend the duty budget on. The Apps tab surfaces the resulting resting state; the
  per-rule duration is the way to stay inside the budget.
- Notification rules ignore ongoing notifications (media, progress) to avoid constant retriggering.
