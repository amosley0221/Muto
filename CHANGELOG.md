# Changelog

Each released version gets a section here. The release workflow publishes the matching section
as the body of the GitHub release; when a version has no section, it falls back to the commit
subjects since the previous tag.

Keep entries written for someone using the app, not someone reading the diff.

## 0.1.0

First release.

### What it does

- Filters DNS on-device through a local VPN tunnel that carries DNS and nothing else. All other
  traffic takes its normal route, at full speed, unseen by Muto.
- Block list subscriptions, with StevenBlack and the AdGuard DNS filter on by default and six
  more available. Custom lists by URL. Reads hosts files, plain domain lists, and the
  whole-domain rules in Adblock Plus lists.
- Rules you write yourself always beat the subscribed lists, so a site a list broke takes one tap
  to fix.
- A live query log showing every decision and the rule behind it, searchable, with allow and
  block available on any entry.
- Per-app bypass, for apps that refuse to run alongside a VPN.
- Pause without tearing the tunnel down, a Quick Settings tile, start after reboot, and scheduled
  list updates that can be held to Wi-Fi.

### What it does not do

- **YouTube and Twitch video ads are not blocked.** They are served from the same hosts as the
  video itself, so no DNS filter can separate them. See `docs/HOW-IT-WORKS.md` for what does work
  on those two.
- Chrome is only partly covered: ads on their own domains go, ads served by the site itself stay,
  and blocked slots leave a gap because nothing can run inside the page.
