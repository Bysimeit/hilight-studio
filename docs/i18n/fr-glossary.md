# French glossary — HiLight Studio

Fixed translations for the terms that recur across the app. The point of a glossary is consistency:
the same English term must not appear as two different French words on two screens.

Mirrors the discipline of [ja-glossary.md](ja-glossary.md).

## Register

- Vouvoiement throughout, as Android's own French does.
- Labels, buttons and pills are noun phrases or infinitives, not sentences, and take no full stop.
- Explanatory captions are sentences and take a full stop.
- French spacing: a narrow non-breaking space before `? ! : ;` and inside `« »`. Written as a normal
  space in the resources, because Android does not reflow them and a non-breaking space in XML is
  easy to lose in an editor.
- No exclamation marks, no emoji. The English copy is deliberately plain and the French should read
  the same way.

## Left in Latin script, never translated

| Term | Why |
|---|---|
| HiLight, HiLight Studio | Product name. Also the Google feature name on the phone |
| Shizuku | Product name |
| ADB, adb | Command name, and the command text itself is untranslated |
| LED | Used as-is in French technical writing |
| Gemini, Pixel | Product names |
| JSON, MessagingStyle, shortcutId | Identifiers a reader would search for verbatim |
| array | Kept as the fork's own word for the eight LEDs — see below |

## Terms

| English | French | Note |
|---|---|---|
| array (the eight LEDs) | l'array | "la barre" collides with the camera bar, "la rampe" reads as stage lighting |
| ambient / always-on look | style permanent | |
| pattern | motif → **but** `pattern_*` names are the effect names, kept as nouns |
| preset | préréglage | |
| rule | règle | |
| per-app rule | règle par appli | "appli", not "application": it is what the tab is called |
| per-contact rule | règle par contact | one name for the feature everywhere |
| chat / conversation | conversation | never "discussion", which Android uses for something else |
| group chat | groupe | |
| notification | notification | |
| notification access | accès aux notifications | as Android's settings screen names it |
| usage access | accès aux données d'utilisation | as Android names it |
| quiet hours | heures calmes | |
| Do Not Disturb | Ne pas déranger | Android's own French |
| Battery Saver | économiseur de batterie | Android's own French |
| low battery | batterie faible | |
| brightness | luminosité | |
| speed / time per cycle | durée d'un cycle | |
| cycle | cycle | |
| fade | fondu | |
| screen off | écran éteint | |
| auto-off | extinction automatique | |
| duty cycle / resting | quota d'allumage / en repos | the safety guard, not a hardware term |
| renderer | renderer | no settled French term; "moteur de rendu" is longer and less precise here |
| helper | assistant | |
| session | session | |
| transport | accès (privileged access) | "transport" is opaque here, as it was in Japanese |
| wireless debugging | débogage sans fil | Android's own French |
| foreground app / while open | appli ouverte | shorter than "au premier plan" and fits the pills |
| incoming call | appel entrant | |
| on a call | en appel | |

## Tabs

The four tab labels have to fit a quarter of a phone's width, which rules out the literal
translations:

| English | French | Why not the literal |
|---|---|---|
| Live | Direct | "En direct" does not fit |
| Style | Style | |
| Apps | Applis | "Applications" does not fit |
| Setup | Config | "Configuration" does not fit |

## The Built-in access card

Extracted from `SetupScreen.kt` into `builtin_*` and translated, so it now follows the phone's
language like every other screen. Terms settled while doing it:

| English | French | Note |
|---|---|---|
| built-in access | accès intégré | the card's own name |
| debug daemon | démon de débogage | the thing that must keep running |
| USB debugging | débogage USB | Android's own French, matching *débogage sans fil* |
| pairing (the one-time step) | association | Android's French for the Wireless debugging dialog |
| to pair | associer | never "appairer", which Android does not use here |
| pairing code | code d'association | as the Settings dialog names it |
| loopback address | adresse de bouclage | |
| developer options | options développeur | Android's own French, no hyphen |

The two sentences about keeping a debug switch on are the longest strings in the app. They are
explanations, not labels, so they take full sentences and full stops; do not compress them into
noun phrases.
