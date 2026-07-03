#!/usr/bin/env bash
set -euo pipefail

# One-time manual login against your xAI SuperGrok / X Premium+ subscription.
# Mints a long-lived refresh token that the app exchanges for short-lived
# access tokens at runtime (see XaiOAuthTokenManager). This script is NOT
# part of the deployed app - run it locally, then copy the printed
# refresh_token into XAI_OAUTH_REFRESH_TOKEN on the deployment.
#
# Uses xAI's OAuth 2.0 device authorization flow (RFC 8628) against the
# same client_id xAI's own CLI tooling uses, scoped to your account only.
#
# Requires: curl, jq

DEVICE_CODE_URL="https://auth.x.ai/oauth2/device/code"
TOKEN_URL="https://auth.x.ai/oauth2/token"
CLIENT_ID="b1a00492-073a-47ea-816f-4c329264a828"
SCOPE="openid profile email offline_access grok-cli:access api:access"
GRANT_TYPE_DEVICE="urn:ietf:params:oauth:grant-type:device_code"

for bin in curl jq; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    echo "Error: $bin is required but not found on PATH." >&2
    exit 1
  fi
done

echo "Requesting device code from xAI..."
device_response=$(curl -sS -X POST "$DEVICE_CODE_URL" \
  --data-urlencode "client_id=${CLIENT_ID}" \
  --data-urlencode "scope=${SCOPE}")

device_code=$(echo "$device_response" | jq -r '.device_code // empty')
user_code=$(echo "$device_response" | jq -r '.user_code // empty')
verification_uri=$(echo "$device_response" | jq -r '.verification_uri // .verification_uri_complete // empty')
interval=$(echo "$device_response" | jq -r '.interval // 5')
expires_in=$(echo "$device_response" | jq -r '.expires_in // 600')

if [[ -z "$device_code" || -z "$user_code" ]]; then
  echo "Error: unexpected response from device code endpoint:" >&2
  echo "$device_response" >&2
  exit 1
fi

echo
echo "1. Open: ${verification_uri}"
echo "2. Enter code: ${user_code}"
echo "3. Approve access with your SuperGrok / X Premium+ account."
echo
echo "Waiting for approval..."

deadline=$((SECONDS + expires_in))
while (( SECONDS < deadline )); do
  sleep "$interval"

  token_response=$(curl -sS -X POST "$TOKEN_URL" \
    --data-urlencode "grant_type=${GRANT_TYPE_DEVICE}" \
    --data-urlencode "device_code=${device_code}" \
    --data-urlencode "client_id=${CLIENT_ID}")

  error=$(echo "$token_response" | jq -r '.error // empty')

  if [[ -z "$error" ]]; then
    access_token=$(echo "$token_response" | jq -r '.access_token // empty')
    refresh_token=$(echo "$token_response" | jq -r '.refresh_token // empty')

    if [[ -z "$refresh_token" ]]; then
      echo "Error: token response had no refresh_token. Full response:" >&2
      echo "$token_response" >&2
      exit 1
    fi

    echo
    echo "Success. Set this on your deployment:"
    echo
    echo "  XAI_OAUTH_REFRESH_TOKEN=${refresh_token}"
    echo
    echo "(access_token also returned, but the app mints its own short-lived access tokens from the refresh token - no need to store it.)"
    exit 0
  fi

  case "$error" in
    authorization_pending) continue ;;
    slow_down) interval=$((interval + 5)); continue ;;
    *)
      echo "Error: device authorization failed: ${error}" >&2
      echo "$token_response" >&2
      exit 1
      ;;
  esac
done

echo "Error: device code expired before approval was completed. Re-run this script." >&2
exit 1
