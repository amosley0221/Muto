# Muto

An Android ad and tracker blocker that filters DNS on-device, using a local `VpnService` that
carries DNS and nothing else.

No root. No traffic interception. No servers — Muto talks to your chosen DNS resolver and nowhere
else.

---

## Read this first: what it blocks and what it does not

Muto works by answering DNS queries. When an app or a web page asks where `ads.example.com` lives,
Muto says it does not exist and the request is never made. That approach is simple, fast and
private, and it draws a hard line around what is possible:

| | Result |
|---|---|
| Ads and trackers in most apps | **Blocked.** These come from dedicated ad domains. |
| Ads on web pages in **Chrome**, Samsung Internet, Edge | **Mostly blocked.** Anything served from a distinct ad domain goes. First-party ads and leftover blank spaces do not — Chrome on Android has no extension support, so nothing can remove those. |
| Analytics and telemetry | **Blocked**, for anything on its own domain. |
| Malware and phishing hosts | **Blocked**, with the security list enabled. |
| **YouTube video ads** | **Not blocked.** They come from the same servers as the video itself. |
| **Twitch stream ads** | **Not blocked.** They are stitched into the video stream before it leaves Twitch. |

That YouTube and Twitch row is not a gap to be closed in a later version — it is a consequence of
how those two services serve ads, and no DNS-based blocker on any platform gets past it.
[docs/HOW-IT-WORKS.md](docs/HOW-IT-WORKS.md) explains exactly why, and lists the approaches that
*do* work on those two services.

If blocking YouTube and Twitch ads is your main goal, Muto is the wrong tool and will disappoint
you. If you want the other ninety per cent of advertising and tracking gone from the whole device
at once, it is a good fit.

---

## How it works, briefly

Muto establishes a VPN tunnel and routes exactly one address into it: its own fake DNS resolver at
`10.83.47.2`. Android then sends DNS queries there and leaves every other byte alone — video,
downloads, uploads, all of it goes straight out over the real interface at full speed, and Muto is
not in a position to see any of it.

For each query, Muto:

1. parses the name being asked about,
2. checks it against the subscribed block lists and your own rules,
3. either answers it locally (blocked) or forwards it to your chosen upstream resolver.

The VPN permission Android asks for is how an app is allowed to see its own device's DNS. It is
not used to route traffic anywhere.

---

## Features

- **Block list subscriptions** — ships with a catalogue (StevenBlack, AdGuard DNS, AdAway, Peter
  Lowe, EasyPrivacy, HaGeZi, URLhaus). Two are on by default; the aggressive ones are opt-in.
  Custom lists by URL. Reads hosts files, plain domain lists, and the whole-domain subset of
  Adblock Plus syntax.
- **Your own rules win** — a domain you allow beats every list, which is how you fix a site a list
  broke without working out which list did it.
- **Live query log** — watch decisions as they happen, search them, and allow or block a domain
  with one tap. History on disk is off by default.
- **Per-app bypass** — exclude an app from the tunnel entirely, for the banking apps that refuse
  to run alongside a VPN.
- **Pause** — stop filtering without tearing the tunnel down or re-prompting for permission.
- **Quick Settings tile**, start-after-reboot, and scheduled list updates that defer to Wi-Fi.
- **Choice of upstream resolver** — follow the network, or pin Cloudflare, Quad9 or Google.
- **Choice of block response** — NXDOMAIN (default), a null IP, or REFUSED.

---

## Installing

Grab the latest `muto-<version>.apk` from the [releases page](../../releases) and open it on the
phone. Android will ask you to allow installing from this source the first time.

**Updating:** install the newer APK straight over the old one. Rules, lists, history and settings
are all kept, and there is no need to uninstall — every release is signed with the same key, which
is what lets Android treat it as an update rather than a different app.

If an update is refused with *"App not installed"*, the copy on the device was signed with a
different key — most often a debug APK from CI, which is deliberately a separate app. Uninstall
that one, install a release, and updates apply in place from then on.
[docs/RELEASING.md](docs/RELEASING.md) covers this in full.

## Building

Requires Android Studio (Ladybug or newer) or a command-line Android SDK, and JDK 17+.

```bash
git clone <this repo>
cd Muto
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

### Project layout

```
core/    Pure Kotlin. DNS wire format, IP/UDP packet handling, domain matching,
         list parsing, rule precedence. No Android dependencies, 60 unit tests.
app/     The Android app. VpnService, packet pump, Room storage, Compose UI.
```

The split is not ceremony: the fiddly, correctness-critical parts — DNS message construction,
UDP checksums over the IPv6 pseudo-header, label-wise suffix matching — all live in `core` where
they can be tested on the JVM in a second, without an emulator.

```bash
./gradlew :core:test          # runs anywhere, no Android SDK needed
./gradlew :app:assembleDebug  # needs the SDK
```

## Continuous integration

- **Build** runs on every push: `core` tests, the debug APK, and lint. The APK is attached to the
  run as an artifact.
- **Release** runs when a `v*` tag is pushed: builds and signs the APK, verifies the signature,
  and publishes it to the releases page with notes.

Releasing needs a signing key set up once — see [docs/RELEASING.md](docs/RELEASING.md).

---

## Privacy

- DNS queries go to the resolver you pick and nowhere else.
- Query history is **off by default**. When you turn it on, it is stored in an app-private
  database on the device, pruned on the schedule you choose, and never leaves the phone.
- Block lists are downloaded from their publishers over HTTPS. Those publishers see your IP
  address when the daily update runs, and nothing else.
- Muto has no analytics, no crash reporting and no network access beyond the two things above.
- Cloud backup deliberately excludes the compiled block lists — settings and rules restore onto a
  new device, the multi-megabyte cache does not.

---

## Licence

Not yet chosen. The bundled block lists are not part of this repository; each is downloaded from
its publisher at runtime under that publisher's own licence.
