#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
command -v gh >/dev/null || pkg install gh
command -v keytool >/dev/null || pkg install openjdk-21
command -v openssl >/dev/null || pkg install openssl
cd "$(dirname "$0")/.."
repo="aliakbar6821/StreamHub"
keydir="$HOME/.config/streamhub-release"
mkdir -p "$keydir"
chmod 700 "$keydir"
key="$keydir/streamhub.p12"
password="$keydir/store-password"
if [[ -e "$key" && ! -s "$password" ]]; then
  echo "STOP: keystore exists but password file is missing. Restore your password backup; do not generate a new key."
  exit 1
fi
if [[ ! -e "$key" ]]; then
  openssl rand -hex 32 > "$password"
  export STREAMHUB_LOCAL_PASS="$(cat "$password")"
  keytool -genkeypair -keystore "$key" -storetype PKCS12 \
    -alias streamhub -keyalg RSA -keysize 3072 -validity 10000 \
    -storepass:env STREAMHUB_LOCAL_PASS -keypass:env STREAMHUB_LOCAL_PASS \
    -dname "CN=StreamHub Release"
  unset STREAMHUB_LOCAL_PASS
fi
# Validate before uploading anything. No key or password is printed.
export STREAMHUB_LOCAL_PASS="$(cat "$password")"
keytool -list -keystore "$key" -alias streamhub -storepass:env STREAMHUB_LOCAL_PASS >/dev/null
unset STREAMHUB_LOCAL_PASS
gh auth status >/dev/null 2>&1 || gh auth login --web --git-protocol https
base64 < "$key" | tr -d '\n' | gh secret set STREAMHUB_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$(cat "$password")" | gh secret set STREAMHUB_STORE_PASSWORD --repo "$repo"
printf '%s' streamhub | gh secret set STREAMHUB_KEY_ALIAS --repo "$repo"
printf '%s' "$(cat "$password")" | gh secret set STREAMHUB_KEY_PASSWORD --repo "$repo"
echo "Signing secrets saved to $repo."
echo "IMPORTANT: securely back up BOTH $key and $password off this device."
echo "Do not commit, upload publicly, or share those two files. Keep the same key for all future updates."
echo "The stable APK installs alongside the debug app. Keep the old app until you migrate your data/videos."
