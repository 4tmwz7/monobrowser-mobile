#!/usr/bin/env bash
# Prints the release keystore as one base64 line for the GitHub secret ANDROID_KEYSTORE_BASE64.
# Usage: scripts/print-keystore-secret.sh [path/to/release.jks]
# Does NOT print any password. Paste the output into: GitHub repo -> Settings -> Secrets and variables -> Actions.
set -euo pipefail

keystore="${1:-${MONO_KEYSTORE_FILE:-$HOME/.config/monobrowser-mobile/release.jks}}"
if [[ ! -f "$keystore" ]]; then
  echo "Keystore not found: $keystore" >&2
  exit 1
fi

echo "# Secret ANDROID_KEYSTORE_BASE64 (value below, one line):" >&2
base64 -w0 "$keystore"
echo
cat >&2 <<'MSG'

# Also set these repository secrets (values are in keystore.properties - keep them private):
#   ANDROID_KEYSTORE_PASSWORD  <- storePassword
#   ANDROID_KEY_ALIAS          <- keyAlias   (monobrowser)
#   ANDROID_KEY_PASSWORD       <- keyPassword
# With the GitHub CLI, from the project root:
#   scripts/print-keystore-secret.sh 2>/dev/null | gh secret set ANDROID_KEYSTORE_BASE64 --repo 4tmwz7/monobrowser-mobile
MSG
