#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_FILE="${SPACES_ENV_FILE:-${ROOT_DIR}/.env.spaces}"
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "${ENV_FILE}"
  set +a
fi

DATA_DIR="${DATA_DIR:-${ROOT_DIR}/data}"
SYNC_DIRECTION="${SYNC_DIRECTION:-up}" # up or down
SPACES_PREFIX="${SPACES_PREFIX:-assets}"
SPACES_ENDPOINT="${SPACES_ENDPOINT:-https://${SPACES_REGION}.digitaloceanspaces.com}"
SPACES_ACL_DEFAULT="public-read"

if ! command -v aws >/dev/null 2>&1; then
  echo "aws CLI not found. Install awscli v2 and retry." >&2
  exit 1
fi

if [[ -z "${SPACES_BUCKET:-}" || -z "${SPACES_REGION:-}" ]]; then
  echo "Missing SPACES_BUCKET or SPACES_REGION." >&2
  exit 1
fi

REMOTE_URI="s3://${SPACES_BUCKET}/${SPACES_PREFIX}"

SYNC_ARGS=(
  --endpoint-url "${SPACES_ENDPOINT}"
  --exclude "*"
  --include "audio/**"
  --include "book-covers/**"
  --include "character-portraits/**"
  --include "illustrations/**"
)

if [[ "${SYNC_DELETE:-false}" == "true" ]]; then
  SYNC_ARGS+=(--delete)
fi

if [[ "${SYNC_DRYRUN:-false}" == "true" ]]; then
  SYNC_ARGS+=(--dryrun)
fi

if [[ -n "${SPACES_ACL:-}" ]]; then
  SYNC_ARGS+=(--acl "${SPACES_ACL}")
elif [[ "${SYNC_DIRECTION}" == "up" ]]; then
  SYNC_ARGS+=(--acl "${SPACES_ACL_DEFAULT}")
fi

case "${SYNC_DIRECTION}" in
  up)
    aws s3 sync "${DATA_DIR}" "${REMOTE_URI}" "${SYNC_ARGS[@]}"
    ;;
  down)
    aws s3 sync "${REMOTE_URI}" "${DATA_DIR}" "${SYNC_ARGS[@]}"
    ;;
  *)
    echo "SYNC_DIRECTION must be 'up' or 'down'." >&2
    exit 1
    ;;
esac
