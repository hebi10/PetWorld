#!/usr/bin/env bash
set -euo pipefail
umask 077

REPO="hebi10/PetWorld"
ENVIRONMENT="petworld-dev-signing"
ALIAS="petworlddev"

for command in gh keytool openssl python3; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing required command: $command" >&2
    exit 1
  fi
done

gh auth status >/dev/null
gh repo view "$REPO" --json nameWithOwner --jq '.nameWithOwner' | grep -Fx "$REPO" >/dev/null
gh api "repos/$REPO/environments/$ENVIRONMENT" >/dev/null || {
  echo "Create and protect GitHub Environment '$ENVIRONMENT' before provisioning." >&2
  exit 1
}

existing="$(gh secret list --repo "$REPO" --env "$ENVIRONMENT" --json name --jq '.[].name')"
if printf '%s\n' "$existing" | grep -Eq '^PETWORLD_DEV_(KEYSTORE_BASE64|STORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)$'; then
  echo "A development signer secret already exists. Refusing to rotate it automatically." >&2
  exit 1
fi

workdir="$(mktemp -d)"
cleanup() {
  if [ -f "$workdir/petworld-dev.p12" ]; then
    shred -u "$workdir/petworld-dev.p12" 2>/dev/null || rm -f "$workdir/petworld-dev.p12"
  fi
  rm -rf "$workdir"
}
trap cleanup EXIT

password="$(openssl rand -hex 24)"
keytool -genkeypair -noprompt \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 3072 \
  -validity 3650 \
  -dname "CN=PetWorld Development, OU=Android Dev, O=PetWorld, C=KR" \
  -storetype PKCS12 \
  -keystore "$workdir/petworld-dev.p12" \
  -storepass "$password" \
  -keypass "$password" \
  > /dev/null

python3 - "$workdir/petworld-dev.p12" <<'PY' |
import base64
import pathlib
import sys
data = pathlib.Path(sys.argv[1]).read_bytes()
sys.stdout.write(base64.b64encode(data).decode("ascii"))
PY
  gh secret set PETWORLD_DEV_KEYSTORE_BASE64 --repo "$REPO" --env "$ENVIRONMENT"

printf '%s' "$password" |
  gh secret set PETWORLD_DEV_STORE_PASSWORD --repo "$REPO" --env "$ENVIRONMENT"
printf '%s' "$password" |
  gh secret set PETWORLD_DEV_KEY_PASSWORD --repo "$REPO" --env "$ENVIRONMENT"
printf '%s' "$ALIAS" |
  gh secret set PETWORLD_DEV_KEY_ALIAS --repo "$REPO" --env "$ENVIRONMENT"

echo "Protected GitHub environment signing secrets stored. No private bytes were printed."
echo "Development-only public fingerprints:"
PETWORLD_DEV_STORE_PASSWORD="$password" keytool -list -v \
  -keystore "$workdir/petworld-dev.p12" \
  -alias "$ALIAS" \
  -storepass:env PETWORLD_DEV_STORE_PASSWORD |
  awk '/^[[:space:]]*SHA1:|^[[:space:]]*SHA256:/ { print }'
echo "Use only for Firebase project petworld-dev / com.haebi.petworld.dev."
