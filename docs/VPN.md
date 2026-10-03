# Tunnel mode: choosing a US exit

Muto's tunnel mode speaks WireGuard and connects to a server you supply. Muto is the client; it
does not run an exit server and never routes your traffic through anything belonging to this
project. This page is about choosing the other end.

## Start here: what you are actually trying to beat

Geo-restriction is not one check. Services stack several, and which ones a service uses decides
whether a VPN is enough:

| Check | What defeats it |
|---|---|
| **IP geolocation** — where the address says you are | Any VPN with an exit in the right country. |
| **IP reputation** — is this a datacenter or a VPN? | A residential IP. Datacenter ranges are widely flagged. |
| **Device location** — GPS and nearby wifi, read from the handset | Nothing a VPN does. The app is reading the phone, not the network. |
| **Account-level region** — billing address, periodic home check-ins | Nothing technical. It is a property of the account. |

Most sites only do the first. Some streaming services do the first two. **YouTube TV does all
four**, which is why it is the hardest case and why a VPN alone is very unlikely to be enough —
see the bottom of this page.

## The options

### A commercial provider

Mullvad, IVPN, Proton, AzireVPN. Around $5/month; all of them hand you a ready WireGuard config
file you can import straight into Muto.

- **Good for** public wifi, general privacy, sites that only check IP geolocation.
- **Weak for** streaming. These are datacenter IPs shared with thousands of people, and the big
  services have had them on blocklists for years. Mullvad says outright that it does not try to
  win this fight.
- **Effort** minutes.

### Your own US VPS

A $4–6/month box at DigitalOcean, Vultr, Linode or Hetzner in a US region.

- **Good for** control, and an IP nobody else is burning. Usually beats a commercial provider for
  streaming simply because the address is not already on a list.
- **Weak for** anything checking the network *type*: the IP still resolves to a hosting company's
  ASN, which is trivially detectable and is exactly what the stricter services look at.
- **Effort** an hour the first time. Ongoing patching is yours.

### A box on a US residential connection

A Raspberry Pi (~$50) or an old laptop at your own house, or a relative's, running WireGuard.

- **Good for** everything above, and it is the only option that produces a genuinely residential
  IP on a consumer ISP's ASN. For geo-restricted streaming this is far and away the most
  effective choice.
- **Weak for** speed — you are limited by that house's *upload* bandwidth, which on most home
  connections is a fraction of the download. Check it before buying hardware; streaming HD needs
  roughly 5–10 Mbps up, sustained.
- **Also** needs either a port forwarded on that router, or a relay such as Tailscale to avoid
  touching the router at all.
- **Effort** a couple of hours, plus someone willing to host it.

**If you have a home in the US that you travel from, this is the answer.** It gives you a
residential IP in the right metro area, which is the only option that lines up with how
account-level region checks work as well as the network ones.

### What to avoid

**Residential proxy services.** They sell access to other people's connections, and the supply
side is frequently built on SDKs bundled into free apps without meaningful consent. You would be
routing your traffic through a stranger's device, and they through yours.

## About YouTube TV specifically

Be realistic about this one. On an Android phone the YouTube TV app requires location permission
and reads the device's actual location. If the handset says Panama, a US exit IP does not help —
the app is not asking the network where you are, it is asking the phone.

On top of that, YouTube TV ties your account to a "home area" from your billing address and
requires you to check in from that home network periodically. That is an account property; no
network configuration changes it.

So:

- **A VPN alone will probably not work**, even with a residential US exit.
- The combination people use is a residential US IP *plus* faking the device's location. Faking
  location on Android means enabling developer options and running a mock-location app. It is
  detectable, it violates YouTube TV's terms, and it puts the account you are paying for at risk.
  Muto does not do it and will not.
- The **web player** reads location from the browser rather than from Android's location service,
  and a browser will not share location if you decline the permission prompt — at which point it
  falls back to IP. That is still a terms-of-service matter between you and Google, but it
  involves declining an optional permission rather than forging anything. Worth trying before
  buying hardware.

None of this is legal advice, and using a VPN to watch a service outside its licensed region is
a breach of that service's terms even where it is not against the law. Your account is what is at
risk, so it is worth knowing the odds before spending money on them.

## What tunnel mode does and does not do

- It is **exclusive with filtering**. Android allows one VPN at a time, so Muto is either
  filtering DNS or running the tunnel. Switching modes tears one down and brings the other up.
- While tunnelled, ad blocking comes from whatever resolver the WireGuard config's `DNS =` line
  points at. Point it at a filtering resolver, or at a Pi-hole on the same network as the exit,
  and you keep most of the benefit.
- Muto does not bundle, recommend or resell any VPN service, and has no servers of its own.
