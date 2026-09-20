<div align="center">

# Pure Schedule

**Pure Schedule, JUWP only, Android only**

[![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.5.2-blue.svg)](../../releases)

[简体中文](README.md) ｜ **English**

</div>

---

> ## ⚠️ Supported school
>
> **This app only works with Jiangxi University of Water Resources and Electric Power (JUWP).**
> The endpoints, the CAS/SSO redirect chain and the timetable HTML structure are all
> hard-coded for this one institution — **accounts from other schools will not work.**

---

## What is this

An Android app that shows your class schedule **without opening a browser**. It signs in to
the university's central authentication with your student ID and password, fetches the whole
semester's timetable, caches it on your device, and keeps working **offline**.

## Why it exists

Typical schedule apps in the local ecosystem suffer from two things: they are **bloated**
(social feeds, second-hand marketplaces, campus gossip walls, news streams) and **full of ads**
(splash ads, feed ads, marketing pushes). Checking when your next class starts should not cost
you any of that.

So this app deliberately does exactly one thing:

| | |
|---|---|
| **No ads** | Not a single ad SDK. No splash ads, no feeds, no marketing pushes |
| **No bloat** | No social, no shop, no news. The feature list below is the whole app |
| **No server** | No backend and no data collection — credentials go only to the school's own auth server |
| **No heavy dependencies** | No Hilt / Navigation / Retrofit: a small, stable, predictable dependency graph |
| **No excess permissions** | Only network, notifications, alarms and boot-start — all required for "view schedule + remind" |

If what you want is "open it, read it, close it", this app is built to that standard.

## Features

| | |
|---|---|
| **Today** | Opens straight to today's classes, with the current teaching week |
| **Week view** | Swipe between weeks; today and the current week are highlighted |
| **Auto term** | Picks the current term by date — no manual switching each semester |
| **Offline** | The timetable is cached locally and viewable without a network |
| **Reminders** | Class notifications 5–60 minutes ahead |
| **Theming** | Six accent colours, light/dark mode, custom background image, translucent cards |
| **Clashing courses** | When a retaken course collides with a regular one, the regular course is drawn with a round "+N" badge; tap to list all of them |
| **Portrait lock** | The grid has 7 columns; in landscape each column is too narrow to read, so portrait is forced |

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | Sign in to the academic system and fetch the timetable |
| `POST_NOTIFICATIONS` | Show class reminders |
| `SCHEDULE_EXACT_ALARM` | Fire reminders on time; falls back to an inexact alarm if not granted |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Chinese OEM ROMs kill background work; used only to guide you to the system settings |
| `RECEIVE_BOOT_COMPLETED` | Re-schedule reminders after a reboot |

**No** access to contacts, location, photos or device identifiers.

## Requirements

- Android 8.0 (API 26) or newer
- The phone must be able to reach the university's academic system (it is publicly reachable —
  **no campus VPN needed**)

> 💡 **Do not run a proxy/VPN app.** Clash-style TUN mode is known to hijack the university's
> domain into a virtual subnet and break connectivity. If the app says it cannot reach the
> academic system, turn the proxy off first.

## Install

Download the APK from the [Releases](../../releases) page and open it on your phone.
Android may warn about an unknown source — that is expected, since the app is not on any store.

A step-by-step guide (in Chinese) is in [`docs/user-guide.md`](docs/user-guide.md).

## Build

```bash
git clone https://github.com/yuluo732/juwp-pure-schedule.git
cd pure-schedule
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Requires **JDK 17+** and **Android SDK (platform 36)**. The Gradle version is pinned by the
wrapper (9.3.0) — no manual install needed.

### Release build (optional)

`assembleRelease` enables R8 shrinking and resource compression: the artefact is roughly
**1/9** the size of the debug build (1.9 MB vs 17.7 MB). Publishing requires **your own key**:

```bash
# 1) Generate a key (pick your own passwords and back them up)
keytool -genkeypair -v -keystore my-release.jks -alias my-alias \
  -keyalg RSA -keysize 2048 -validity 10000

# 2) Configure (template: keystore.properties.example)
cp keystore.properties.example keystore.properties
# then edit keystore.properties with the passwords you chose

# 3) Build
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

> `keystore.properties` and `*.jks` are **excluded by `.gitignore`** and will not be committed.
> Without a configured key, `assembleRelease` still succeeds but falls back to the **debug
> signature**, which is unsuitable for distribution (the signature changes across machines,
> so users cannot upgrade in place).

⚠️ **Back up your signing key in several places.** If it is lost, you cannot ship an in-place
upgrade for already-installed versions — users would have to uninstall and reinstall, losing
their cached timetable and settings.

## How it works

```
student ID + password
   ↓
CAS central authentication (spans 4 host/port combinations; redirects must be followed
manually hop-by-hop, or cookies are dropped on cross-protocol jumps)
   ↓
SSO establishes the academic-system session
   ↓
GET /jsxsd/xskb/xskb_list.do?viweType=0   ← without ?viweType=0 you only get an empty iframe shell
   ↓
Parse HTML with Jsoup → persist to Room → render with Compose
```

See section 2 ("Three iron rules") of [`AGENTS.md`](AGENTS.md) for why the login chain must be
written this way and which URL actually returns the timetable.

## Project layout

```
app/src/main/java/com/juwp/schedule/
├── data/
│   ├── net/          login chain (all URLs and regexes live in JwUrls)
│   ├── parse/        timetable HTML parser
│   ├── db/           Room entities / DAOs
│   ├── prefs/        DataStore settings
│   └── repo/         orchestrates login → fetch → parse → persist
├── domain/           teaching-week maths and term matching (pure logic, testable offline)
├── reminder/         AlarmManager scheduling and notifications
└── ui/               Compose screens (Today / Week / Settings)

validation/ParserCheck.kt   49 parser assertions (synthetic fixture, no Android device needed)
validation/fixtures/        synthetic timetable HTML (real structure, fictional content)
tools/                      development and maintenance tooling (see tools/README.md)
```

## Known limitations

| Limitation | Notes |
|---|---|
| One school only | See the notice at the top. Other schools require rewriting `data/net/JwUrls.kt` and the parser |
| Android only | No iOS or web version is planned |
| The "term start date" dialog is wide | Android's `DatePicker` hard-codes `360dp` width, which is nearly the full screen at this device density. **There is no official parameter to shrink it** |
| Portrait lock | The grid has 7 columns; in landscape each column is under 1/10 of the screen width |
| No back-to-back class sample | The `rowspan` (multi-row card) logic is covered by offline assertions but has not yet appeared in real data |

## Contributing

Found a bug? Open an issue. Before changing code, please read **[`AGENTS.md`](AGENTS.md)** —
it records what has already been verified and which approaches were tried and **failed**,
so you don't repeat them (for example, "dialog size cannot be scaled with density" was
tested and disproved).

## License

[MIT](LICENSE) © 2026 Pure Schedule contributors

---

<div align="center">
<sub>

**Disclaimer**: An unofficial tool for personal use, not affiliated with JUWP.
Please comply with your institution's policies when using it.

</sub>
</div>
