# Architecture

## Modules

```
core/                    Plain Kotlin. No Android. 60 unit tests.
  dns/DnsMessage         DNS wire format: parse a question, build a blocked answer.
  net/IpPackets          IPv4/IPv6 + UDP parse and build, including checksums.
  filter/DomainSet       Sorted 64-bit hash array with label-wise suffix matching.
  filter/DomainNames     Normalisation and validation shared by parser, UI and matcher.
  filter/BlocklistParser Hosts files, plain domain lists, whole-domain ABP rules.
  filter/FilterEngine    Rule precedence over an immutable, hot-swappable snapshot.
  filter/BuiltInLists    The shipped catalogue of sources and upstream resolvers.
  stats/FilterStats      Atomic counters for the home screen.

app/                     The Android app.
  vpn/MutoVpnService     Tunnel lifecycle, foreground notification, settings reaction.
  vpn/DnsPacketPump      The three-thread packet loop.
  vpn/UpstreamDnsProvider Finds the non-VPN network's resolvers.
  vpn/TunnelAddresses    The addressing constants, and why they are what they are.
  data/                  Room, DataStore, list download and the compiled-list cache.
  work/                  The periodic list update job.
  ui/                    Compose Material3, one view model.
  tile/, receiver/       Quick Settings tile and boot restart.
```

`core` exists so the parts that are easy to get subtly wrong — a UDP checksum over the IPv6
pseudo-header, a compression pointer that loops, `notdoubleclick.net` matching `doubleclick.net` —
can be tested in a second on the JVM instead of on a device. It also means those tests run in any
CI without an Android SDK:

```bash
./gradlew :core:test --configure-on-demand
```

The root build script deliberately does not apply the Android plugin, which is what makes that
command work without the SDK installed.

## Data flow for one query

```
app asks for ads.example.com
        │
        ▼
Android resolver sends UDP to 10.83.47.2:53   (the only route in the tunnel)
        │
        ▼
DnsPacketPump reader thread
  IpPackets.parseUdp     → source/destination, ports, payload
  DnsMessage.parseQuestion → "ads.example.com", type A
  FilterEngine.decide    → Verdict(blocked, reason, matching rule)
        │
   ┌────┴─────────────────────────────┐
   ▼ blocked                          ▼ allowed
DnsMessage.buildBlockedResponse   protected socket → upstream resolver
IpPackets.buildUdpReply                 │ (selector thread waits for the reply)
   │                                    ▼
   └────────────► writer thread ◄── IpPackets.buildUdpReply
                        │
                        ▼
                  back into the TUN
```

## Threading

| Thread | Job |
|---|---|
| `muto-tun-read` | Blocking read on the TUN descriptor; makes the filter decision. |
| `muto-upstream` | `Selector` over in-flight upstream sockets; also sweeps timed-out queries. |
| `muto-tun-write` | Drains a bounded queue into the TUN descriptor. |
| app coroutines | List downloads, rule compilation, UI. Never touches the packet path. |

The rule set is the only state shared between the UI and the packet path, and it is shared as an
immutable snapshot behind an `AtomicReference` — so the reader thread never takes a lock and never
observes a partially built rule set.

Bounds that matter: at most 512 upstream queries in flight (beyond that, queries are dropped and
the client retries, which is better than exhausting the process's file descriptors), a 1024-deep
write queue, and a five-second upstream timeout.
