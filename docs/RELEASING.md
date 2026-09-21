# Releasing

## One-time setup: the signing key

Android identifies an app by its signing key as much as by its package name. An APK signed with a
different key **cannot install over one already on the device** — it fails with *"App not
installed"*, and the only way through is to uninstall, which takes your rules and settings with
it.

So every release has to be signed with the same key, and that key has to outlive any individual
machine. Create it once:

```bash
./tools/setup-signing.sh
```

That generates `muto-release.jks` with a random 40-character password and, if the GitHub CLI is
signed in, sets the four repository secrets the release workflow needs:

| Secret | What it is |
|---|---|
| `MUTO_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `MUTO_KEYSTORE_PASSWORD` | the store password |
| `MUTO_KEY_ALIAS` | `muto` |
| `MUTO_KEY_PASSWORD` | the key password (same as the store password) |

If `gh` is not available the script prints the four values to paste into **Settings → Secrets and
variables → Actions**.

**Back up `muto-release.jks` and its password properly** — a password manager, not just the
machine you ran it on. Losing it means no future build can update an installed copy of Muto, for
anyone, ever. There is no recovery path.

`*.jks` is in `.gitignore`. Do not commit the key.

## Cutting a release

```bash
git tag v0.2.0
git push origin v0.2.0
```

The `Release` workflow then:

1. checks the tag is `vMAJOR.MINOR.PATCH` and derives `versionCode` from it,
2. runs the `core` tests,
3. builds and signs `app-release.apk`,
4. verifies with `apksigner` that the APK really is signed, and prints the certificate digest,
5. assembles the release notes,
6. publishes a GitHub release with `muto-<version>.apk` attached.

Re-running the workflow for an existing tag replaces the asset and the notes rather than failing.

### The version number

`versionCode` is computed as `major × 10000 + minor × 100 + patch`, so `v1.2.3` becomes `10203`.
Android refuses to install an APK whose `versionCode` is not higher than the installed one, so
**each release must have a higher version than the last**. Going from `v0.2.0` to `v0.1.9` will
produce an APK that cannot be installed over `v0.2.0`.

The scheme allows minor and patch up to 99. If you ever need a hundredth patch release in one
minor series, bump the minor instead.

### Release notes

The workflow prefers a hand-written section in `CHANGELOG.md` whose heading matches the version:

```markdown
## 0.2.0

### Fixed
- Twitch chat no longer breaks when the AdGuard list is enabled.
```

If there is no matching section it falls back to the commit subjects since the previous tag.
Prefer writing the section: commit subjects describe the diff, and a release note should describe
what changed for someone using the app.

Either way, the workflow appends the installing-and-updating instructions to the end.

## Updating a phone without uninstalling

Download the new APK from the releases page and open it. It installs over the existing copy,
keeping the database of rules, the subscribed lists, the query history and every setting.

This works because of three things the build takes care of:

- **the same signing key on every release** — the key from `setup-signing.sh`,
- **a version code that always increases** — derived from the tag,
- **a stable application id** — `dev.muto.app`, which never changes.

### When it does not work

*"App not installed"* on an update means the installed copy has a different signature. The usual
causes:

- **You installed a debug APK from the Build workflow.** Those use the application id
  `dev.muto.app.debug` and Android's throwaway debug key, so they are a separate app. You can keep
  both installed; just do not expect one to update the other.
- **You installed an APK built on your own machine.** A local `assembleRelease` without the
  signing environment variables produces an unsigned APK, and `assembleDebug` uses your machine's
  debug key. Neither matches the release key.
- **The signing key changed.** If `setup-signing.sh` was run a second time and the new key
  replaced the old secrets, every previously installed copy is now unupdateable.

In each case: uninstall once, install the release APK, and updates apply in place from then on.

You can confirm which key an APK carries:

```bash
apksigner verify --print-certs muto-0.2.0.apk
```

The certificate SHA-256 must be identical across releases. The release workflow prints it on
every run, so it is in the log of each build if you need to compare.
