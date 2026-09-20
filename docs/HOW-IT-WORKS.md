# How Muto works, and where DNS filtering runs out

## The tunnel

Muto is a `VpnService`, but it is not a VPN in the sense people usually mean. It routes a single
address into the tunnel:

```
addAddress("10.83.47.1", 32)     // Muto's end of the tunnel
addDnsServer("10.83.47.2")       // the resolver Android will now use
addRoute("10.83.47.2", 32)       // ...and the only thing routed in
```

Everything else — every video frame, every upload, every byte of TLS — takes the ordinary route
over the real interface. It does not pass through Muto, it is not slowed by Muto, and Muto could
not read it if it wanted to. The same three lines exist for IPv6, using a unique-local prefix.

Packets that do arrive are UDP datagrams addressed to port 53. For each one Muto parses the
question, asks the filter engine about the name, and then either writes a synthesised answer back
into the tunnel or relays the query to the real resolver over a socket the VPN framework has been
asked to leave outside the tunnel (`VpnService.protect`). Without that call the query would be
routed back into Muto and loop forever.

Three threads do the work, so neither hot path waits on the other: one blocks on the TUN file
descriptor, one waits on upstream sockets with a `Selector`, and one drains a queue back into the
device.

## The filter

Rules are compiled into a sorted array of 64-bit hashes and matched by binary search, label by
label: `img.ads.example.com` is checked as itself, then `ads.example.com`, then `example.com`, and
stops before the bare TLD. Storing hashes rather than strings is what makes a 200,000-entry list
cost about 1.6 MB instead of 16 MB — which matters, because the process holding that resident is
a background service that Android is looking for excuses to kill.

Precedence is fixed and runs most-specific-intent first:

1. a domain **you** allowed
2. a domain **you** blocked
3. an exception from a subscribed list
4. a block from a subscribed list
5. otherwise, allowed

Your own rules beating the lists is what makes "this site is broken, unblock it" a one-tap fix
rather than an archaeology exercise.

Compiling a new rule set never touches the packet path. The result is published as one immutable
snapshot and the pump picks it up on its next query.

---

## Why YouTube ads get through

YouTube serves its video from `*.googlevideo.com`. It serves its **ads** from `*.googlevideo.com`
too — the same hosts, often the same connection, and increasingly spliced into the same media
stream before it leaves Google. There is no hostname that means "ad" and not "video".

A DNS filter's only lever is the hostname. So the choice is:

- block those hosts, and YouTube stops working entirely, or
- allow them, and the ads come through with the video.

Neither is a bug in Muto, and no DNS-based blocker on any platform — Pi-hole, AdGuard Home,
NextDNS, Blokada — gets past it, for the same reason.

**What does work on Android:**

- **A browser with real content blocking.** Firefox for Android supports uBlock Origin, which
  works on `m.youtube.com` because it can act on the page itself, not just the hostname.
- **An alternative client** such as NewPipe or a similar front end, which fetches the streams
  directly rather than through YouTube's player.
- **YouTube Premium**, which is the only option Google supports.

Muto still does useful work on YouTube: it blocks the advertising and measurement domains the app
talks to alongside the video, and every ad domain in the rest of Google's apps.

## Why Twitch ads get through

Twitch uses server-side ad insertion. The ad segments are written into the HLS playlist by the
same edge servers that serve the stream, carry the same hostnames, and arrive over the same
connection as the content. Again, the hostname carries no signal.

The approaches that do defeat it work at the playlist level — intercepting the request for the
`.m3u8` manifest and substituting one fetched from a region or client type that is not being
served ads. That requires terminating and rewriting Twitch's HTTPS traffic, which is a completely
different architecture from a DNS filter: it means a local TLS man-in-the-middle with a
user-installed root certificate, and it means the blocker can read the traffic it intercepts.
Muto deliberately does not do that, which is also why it can honestly say it cannot see anything
you do.

Muto does block Twitch's display advertising and tracking domains, which are ordinary
third-party hosts.

## Why Chrome is only partly covered

Chrome on Android does not support extensions, so nothing can run inside the page. DNS filtering
removes the network requests to ad domains, which takes out most ads, but:

- an ad served from the site's own domain cannot be distinguished from the site,
- the space the ad occupied stays blank, because nothing collapsed the element.

Firefox for Android supports uBlock Origin and covers both. Using it alongside Muto is a
reasonable arrangement: Muto handles every other app on the device, uBlock handles the pages.

---

## Things that can route around Muto

**Android's Private DNS setting.** If Settings → Network → Private DNS is set to a specific
hostname, Android sends DNS over TLS straight to that server and Muto never sees it. Set it to
"Automatic" or "Off" for Muto to work.

**Apps with a hard-coded DNS-over-HTTPS resolver.** A handful of apps ship their own resolver and
ignore the system one. The "Encrypted-DNS bypass blocking" list in Muto's catalogue blocks the
hosts those apps use to reach their resolvers, which pushes them back onto the system resolver —
at the cost of also breaking DoH in apps where you may want it. It is off by default for that
reason.

**Hard-coded IP addresses.** An app that connects to `203.0.113.10` directly never makes a DNS
query. This is rare, because it makes an app impossible to operate, but it is unfilterable by
design.

**Another VPN.** Android allows one VPN at a time. Muto and a commercial VPN cannot both be on.

---

## Known gaps in the current build

- **No TCP DNS.** Queries sent to port 53 over TCP are dropped. In practice clients only try TCP
  after receiving a truncated reply, and Muto never sends one, so this has no observable effect —
  but it means a client configured to use TCP exclusively would get nothing.
- **No per-app attribution in the log.** The log shows which name was asked for, not which app
  asked. Recovering the app reliably from a tunnelled UDP packet is not something Android offers,
  and a guess would be worse than the blank.
- **No DNS-over-HTTPS upstream.** Queries from Muto to your chosen resolver go out as plain DNS.
  Your network operator can see them, exactly as they could before Muto was installed. Supporting
  DoH upstream is a natural next step and would be a strict improvement.
- **IPv6 extension headers are dropped.** A DNS query carrying one is discarded rather than
  parsed. The system resolver does not send these.
