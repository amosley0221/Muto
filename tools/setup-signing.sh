#!/usr/bin/env bash
#
# Creates the signing key Muto's releases are signed with, and loads it into this repository's
# GitHub secrets. Run it once, on a machine you trust, and keep the key file it produces.
#
# Why this exists: Android identifies an app by its signing key as much as by its package name.
# An APK signed with a different key cannot install over one already on the device - it fails
# with "App not installed" and the only way through is to uninstall, losing your rules and
# settings. So every release has to be signed with this one key, forever.
#
#   ./tools/setup-signing.sh
#
set -euo pipefail

KEYSTORE="${1:-muto-release.jks}"
ALIAS="muto"
VALIDITY_DAYS=10950 # thirty years; an expired key cannot sign a new release

if [ -f "$KEYSTORE" ]; then
  echo "error: $KEYSTORE already exists."
  echo
  echo "If this is the key your existing releases were signed with, do NOT replace it - anyone"
  echo "who installed those releases would be unable to update. Load it into GitHub instead:"
  echo "  base64 -w0 \"$KEYSTORE\"   # paste as the MUTO_KEYSTORE_BASE64 secret"
  exit 1
fi

for tool in keytool base64; do
  command -v "$tool" >/dev/null || { echo "error: $tool is not on PATH (keytool ships with a JDK)"; exit 1; }
done

# Generated rather than chosen: this password is only ever handled by the script and by GitHub.
STORE_PASSWORD="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40)"

echo "Creating $KEYSTORE ..."
keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity "$VALIDITY_DAYS" \
  -storepass "$STORE_PASSWORD" \
  -keypass "$STORE_PASSWORD" \
  -dname "CN=Muto, OU=Muto, O=Muto, L=, ST=, C=" \
  >/dev/null

KEYSTORE_BASE64="$(base64 -w0 "$KEYSTORE" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n')"

echo
if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  echo "Loading the secrets into GitHub ..."
  printf '%s' "$KEYSTORE_BASE64"  | gh secret set MUTO_KEYSTORE_BASE64
  printf '%s' "$STORE_PASSWORD"   | gh secret set MUTO_KEYSTORE_PASSWORD
  printf '%s' "$ALIAS"            | gh secret set MUTO_KEY_ALIAS
  printf '%s' "$STORE_PASSWORD"   | gh secret set MUTO_KEY_PASSWORD
  echo "Done. Four secrets are set."
else
  echo "The GitHub CLI is not available or not signed in, so set these four secrets by hand at"
  echo "  Settings -> Secrets and variables -> Actions -> New repository secret"
  echo
  echo "  MUTO_KEYSTORE_PASSWORD   $STORE_PASSWORD"
  echo "  MUTO_KEY_PASSWORD        $STORE_PASSWORD"
  echo "  MUTO_KEY_ALIAS           $ALIAS"
  echo "  MUTO_KEYSTORE_BASE64     (the long line below)"
  echo
  echo "$KEYSTORE_BASE64"
fi

cat <<NOTE

------------------------------------------------------------------------------
Keep $KEYSTORE and the password somewhere you will still have them in
five years - a password manager, not just this machine.

If you lose this key you cannot publish an update that installs over the
releases signed with it. Everyone who has Muto installed would have to
uninstall and start over. There is no recovery path; this is how Android works.

$KEYSTORE is already covered by .gitignore. Do not commit it.
------------------------------------------------------------------------------
NOTE
