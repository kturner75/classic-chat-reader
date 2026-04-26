#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

GUTENBERG_ID=""
API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
PREGEN_POLL_SECONDS="${PREGEN_POLL_SECONDS:-10}"
PREGEN_TIMEOUT_SECONDS="${PREGEN_TIMEOUT_SECONDS:-7200}"
SYNC_ASSETS=true
SYNC_DIRECTION="${SYNC_DIRECTION:-up}"
SKIP_PREGEN=false
QUIZ_PREGEN=true
QUIZ_POLL_INTERVAL_SECONDS="${QUIZ_POLL_INTERVAL_SECONDS:-5}"
QUIZ_MAX_WAIT_MINUTES="${QUIZ_MAX_WAIT_MINUTES:-0}"
SKIP_DB_TRANSFER=false
TRANSFER_FEATURE="${TRANSFER_FEATURE:-all}" # recaps|quizzes|illustrations|portraits|covers|all
ON_CONFLICT="${ON_CONFLICT:-skip}"
APPLY_IMPORT=true

REMOTE_TARGET=""
REMOTE_PROJECT_DIR=""
REMOTE_DB_URL=""
REMOTE_DB_USER="${REMOTE_DB_USER:-sa}"
REMOTE_DB_PASSWORD="${REMOTE_DB_PASSWORD:-}"
REMOTE_RUNNER="${REMOTE_RUNNER:-auto}"
REMOTE_JAR_PATH="${REMOTE_JAR_PATH:-}"
REMOTE_IMPORT_PATH="${REMOTE_IMPORT_PATH:-}"
REMOTE_STOP_CMD="${REMOTE_STOP_CMD:-}"
REMOTE_START_CMD="${REMOTE_START_CMD:-}"
SSH_CONFIG_PATH="${SSH_CONFIG_PATH:-}"
declare -a SSH_OPTIONS=()
MAVEN_BIN="${MAVEN_BIN:-mvn}"

usage() {
  cat <<EOF
Usage:
  scripts/publish_book_remote.sh --gutenberg-id <id> --remote <host-or-alias> --remote-project-dir <path> --remote-db-url <jdbc-url> [options]

Required:
  --gutenberg-id <id>              Project Gutenberg ID.
  --remote <host-or-alias>         SSH target for remote DB import.
  --remote-project-dir <path>      Project path on remote host.
  --remote-db-url <jdbc-url>       Remote DB URL for import.

Options:
  --api-base-url <url>             Local API base URL for pre-generation (default: ${API_BASE_URL}).
  --skip-pregen                    Skip pre-generation start/poll.
  --skip-quiz-pregen               Skip quiz pre-generation step.
  --skip-sync-assets               Skip Spaces sync (enabled by default).
  --sync-direction <up|down>       Passed to sync_spaces.sh when sync is enabled (default: ${SYNC_DIRECTION}).
  --pregen-poll-seconds <sec>      Poll interval for pre-generation status (default: ${PREGEN_POLL_SECONDS}).
  --pregen-timeout-seconds <sec>   Timeout for pre-generation status polling (default: ${PREGEN_TIMEOUT_SECONDS}).
  --quiz-poll-interval-seconds <n> Poll interval for quiz status polling (default: ${QUIZ_POLL_INTERVAL_SECONDS}).
  --quiz-max-wait-minutes <n>      Max wait for quiz pregen (0 = auto timeout, default: ${QUIZ_MAX_WAIT_MINUTES}).

  --skip-db-transfer               Skip remote DB transfer.
  --transfer-feature <f>           recaps|quizzes|illustrations|portraits|covers|all (default: ${TRANSFER_FEATURE}).
  --on-conflict <skip|overwrite>   Import conflict mode (default: ${ON_CONFLICT}).
  --transfer-dry-run               Run remote transfer dry-run only (default is apply).

  --remote-db-user <user>          Remote DB user (default: ${REMOTE_DB_USER}).
  --remote-db-password <pass>      Remote DB password (default: empty).
  --remote-runner <auto|maven|jar> Remote transfer runner mode (default: ${REMOTE_RUNNER}).
  --remote-jar-path <path>         Remote jar path for runner=jar.
  --remote-import-path <path>      Remote transfer JSON path/base path.
  --remote-stop-cmd "<cmd>"        Optional stop command before apply import.
  --remote-start-cmd "<cmd>"       Optional start command after apply import.
  --ssh-config <path>              Optional SSH config path.
  --ssh-option <k=v>               Extra SSH -o option (repeatable).
  --maven-bin <bin>                Maven executable for local CLI invocations.

  --help                           Show this help.
EOF
}

fail() {
  echo "Error: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --gutenberg-id)
      [[ $# -ge 2 ]] || fail "--gutenberg-id requires a value"
      GUTENBERG_ID="$2"
      shift 2
      ;;
    --api-base-url)
      [[ $# -ge 2 ]] || fail "--api-base-url requires a value"
      API_BASE_URL="$2"
      shift 2
      ;;
    --skip-pregen)
      SKIP_PREGEN=true
      shift
      ;;
    --skip-quiz-pregen)
      QUIZ_PREGEN=false
      shift
      ;;
    --skip-sync-assets)
      SYNC_ASSETS=false
      shift
      ;;
    --sync-direction)
      [[ $# -ge 2 ]] || fail "--sync-direction requires a value"
      SYNC_DIRECTION="$2"
      shift 2
      ;;
    --pregen-poll-seconds)
      [[ $# -ge 2 ]] || fail "--pregen-poll-seconds requires a value"
      PREGEN_POLL_SECONDS="$2"
      shift 2
      ;;
    --pregen-timeout-seconds)
      [[ $# -ge 2 ]] || fail "--pregen-timeout-seconds requires a value"
      PREGEN_TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    --quiz-poll-interval-seconds)
      [[ $# -ge 2 ]] || fail "--quiz-poll-interval-seconds requires a value"
      QUIZ_POLL_INTERVAL_SECONDS="$2"
      shift 2
      ;;
    --quiz-max-wait-minutes)
      [[ $# -ge 2 ]] || fail "--quiz-max-wait-minutes requires a value"
      QUIZ_MAX_WAIT_MINUTES="$2"
      shift 2
      ;;
    --skip-db-transfer)
      SKIP_DB_TRANSFER=true
      shift
      ;;
    --transfer-feature)
      [[ $# -ge 2 ]] || fail "--transfer-feature requires a value"
      TRANSFER_FEATURE="$2"
      shift 2
      ;;
    --on-conflict)
      [[ $# -ge 2 ]] || fail "--on-conflict requires a value"
      ON_CONFLICT="$2"
      shift 2
      ;;
    --transfer-dry-run)
      APPLY_IMPORT=false
      shift
      ;;
    --remote)
      [[ $# -ge 2 ]] || fail "--remote requires a value"
      REMOTE_TARGET="$2"
      shift 2
      ;;
    --remote-project-dir)
      [[ $# -ge 2 ]] || fail "--remote-project-dir requires a value"
      REMOTE_PROJECT_DIR="$2"
      shift 2
      ;;
    --remote-db-url)
      [[ $# -ge 2 ]] || fail "--remote-db-url requires a value"
      REMOTE_DB_URL="$2"
      shift 2
      ;;
    --remote-db-user)
      [[ $# -ge 2 ]] || fail "--remote-db-user requires a value"
      REMOTE_DB_USER="$2"
      shift 2
      ;;
    --remote-db-password)
      [[ $# -ge 2 ]] || fail "--remote-db-password requires a value"
      REMOTE_DB_PASSWORD="$2"
      shift 2
      ;;
    --remote-runner)
      [[ $# -ge 2 ]] || fail "--remote-runner requires a value"
      REMOTE_RUNNER="$2"
      shift 2
      ;;
    --remote-jar-path)
      [[ $# -ge 2 ]] || fail "--remote-jar-path requires a value"
      REMOTE_JAR_PATH="$2"
      shift 2
      ;;
    --remote-import-path)
      [[ $# -ge 2 ]] || fail "--remote-import-path requires a value"
      REMOTE_IMPORT_PATH="$2"
      shift 2
      ;;
    --remote-stop-cmd)
      [[ $# -ge 2 ]] || fail "--remote-stop-cmd requires a value"
      REMOTE_STOP_CMD="$2"
      shift 2
      ;;
    --remote-start-cmd)
      [[ $# -ge 2 ]] || fail "--remote-start-cmd requires a value"
      REMOTE_START_CMD="$2"
      shift 2
      ;;
    --ssh-config)
      [[ $# -ge 2 ]] || fail "--ssh-config requires a value"
      SSH_CONFIG_PATH="$2"
      shift 2
      ;;
    --ssh-option)
      [[ $# -ge 2 ]] || fail "--ssh-option requires a value"
      SSH_OPTIONS+=("$2")
      shift 2
      ;;
    --maven-bin)
      [[ $# -ge 2 ]] || fail "--maven-bin requires a value"
      MAVEN_BIN="$2"
      shift 2
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done

[[ -n "$GUTENBERG_ID" ]] || fail "--gutenberg-id is required"
[[ "$SYNC_DIRECTION" == "up" || "$SYNC_DIRECTION" == "down" ]] || fail "--sync-direction must be up or down"
[[ "$ON_CONFLICT" == "skip" || "$ON_CONFLICT" == "overwrite" ]] || fail "--on-conflict must be skip or overwrite"
[[ "$TRANSFER_FEATURE" == "recaps" || "$TRANSFER_FEATURE" == "quizzes" || "$TRANSFER_FEATURE" == "illustrations" || "$TRANSFER_FEATURE" == "portraits" || "$TRANSFER_FEATURE" == "covers" || "$TRANSFER_FEATURE" == "all" ]] || fail "--transfer-feature must be recaps, quizzes, illustrations, portraits, covers, or all"
[[ "$QUIZ_POLL_INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || fail "--quiz-poll-interval-seconds must be an integer"
[[ "$QUIZ_MAX_WAIT_MINUTES" =~ ^[0-9]+$ ]] || fail "--quiz-max-wait-minutes must be an integer"
(( QUIZ_POLL_INTERVAL_SECONDS > 0 )) || fail "--quiz-poll-interval-seconds must be > 0"

if [[ "$SKIP_DB_TRANSFER" == "false" ]]; then
  [[ -n "$REMOTE_TARGET" ]] || fail "--remote is required unless --skip-db-transfer is set"
  [[ -n "$REMOTE_PROJECT_DIR" ]] || fail "--remote-project-dir is required unless --skip-db-transfer is set"
  [[ -n "$REMOTE_DB_URL" ]] || fail "--remote-db-url is required unless --skip-db-transfer is set"
fi

resolve_book_id() {
  command -v curl >/dev/null 2>&1 || fail "Missing required command: curl"
  command -v jq >/dev/null 2>&1 || fail "Missing required command: jq"

  local response_file status_code response_body resolved_book_id
  response_file="$(mktemp)"
  status_code="$(curl --silent --show-error --output "${response_file}" --write-out '%{http_code}' \
    -X POST "${API_BASE_URL}/api/import/gutenberg/${GUTENBERG_ID}")"
  response_body="$(cat "${response_file}" 2>/dev/null || true)"
  rm -f "${response_file}"

  case "${status_code}" in
    200|409)
      ;;
    *)
      echo "${response_body}" >&2
      fail "Failed to resolve local bookId for Gutenberg ${GUTENBERG_ID} (HTTP ${status_code})"
      ;;
  esac

  resolved_book_id="$(printf '%s' "${response_body}" | jq -r '.bookId // empty')"
  [[ -n "${resolved_book_id}" ]] || fail "Import API did not return bookId for Gutenberg ${GUTENBERG_ID}"
  printf '%s\n' "${resolved_book_id}"
}

echo "Workflow start"
echo "  Gutenberg ID      : ${GUTENBERG_ID}"
echo "  Transfer feature  : ${TRANSFER_FEATURE}"
echo "  Quiz pregen       : ${QUIZ_PREGEN}"
echo "  Sync assets       : ${SYNC_ASSETS}"
echo "  DB transfer apply : ${APPLY_IMPORT}"

if [[ "$SKIP_PREGEN" == "false" || "$SYNC_ASSETS" == "true" ]]; then
  pregen_cmd=(
    "${ROOT_DIR}/scripts/pregen_transfer_book.sh"
    --gutenberg-id "$GUTENBERG_ID"
    --api-base-url "$API_BASE_URL"
    --pregen-poll-seconds "$PREGEN_POLL_SECONDS"
    --pregen-timeout-seconds "$PREGEN_TIMEOUT_SECONDS"
    --skip-export
  )
  if [[ "$SKIP_PREGEN" == "true" ]]; then
    pregen_cmd+=(--skip-pregen)
  fi
  if [[ "$SYNC_ASSETS" == "true" ]]; then
    pregen_cmd+=(--sync-assets --sync-direction "$SYNC_DIRECTION")
  fi
  echo ""
  echo "[1/3] Running local pre-generation/spaces sync workflow..."
  "${pregen_cmd[@]}"
else
  echo ""
  echo "[1/3] Skipped local pre-generation/spaces sync."
fi

if [[ "$QUIZ_PREGEN" == "true" ]]; then
  local_book_id="$(resolve_book_id)"
  echo ""
  echo "[2/3] Running local quiz pre-generation for book ${local_book_id}..."
  "${ROOT_DIR}/scripts/pregen_quizzes_book.sh" \
    --book-id "${local_book_id}" \
    --api-base-url "${API_BASE_URL}" \
    --poll-interval-seconds "${QUIZ_POLL_INTERVAL_SECONDS}" \
    --max-wait-minutes "${QUIZ_MAX_WAIT_MINUTES}"
else
  echo ""
  echo "[2/3] Skipped quiz pre-generation."
fi

if [[ "$SKIP_DB_TRANSFER" == "false" ]]; then
  transfer_cmd=(
    "${ROOT_DIR}/scripts/transfer_recaps_remote.sh"
    --feature "$TRANSFER_FEATURE"
    --book-source-id "$GUTENBERG_ID"
    --remote "$REMOTE_TARGET"
    --remote-project-dir "$REMOTE_PROJECT_DIR"
    --remote-db-url "$REMOTE_DB_URL"
    --remote-db-user "$REMOTE_DB_USER"
    --on-conflict "$ON_CONFLICT"
    --remote-runner "$REMOTE_RUNNER"
    --maven-bin "$MAVEN_BIN"
  )
  if [[ -n "$REMOTE_DB_PASSWORD" ]]; then
    transfer_cmd+=(--remote-db-password "$REMOTE_DB_PASSWORD")
  fi
  if [[ -n "$REMOTE_JAR_PATH" ]]; then
    transfer_cmd+=(--remote-jar-path "$REMOTE_JAR_PATH")
  fi
  if [[ -n "$REMOTE_IMPORT_PATH" ]]; then
    transfer_cmd+=(--remote-import-path "$REMOTE_IMPORT_PATH")
  fi
  if [[ -n "$REMOTE_STOP_CMD" ]]; then
    transfer_cmd+=(--remote-stop-cmd "$REMOTE_STOP_CMD")
  fi
  if [[ -n "$REMOTE_START_CMD" ]]; then
    transfer_cmd+=(--remote-start-cmd "$REMOTE_START_CMD")
  fi
  if [[ -n "$SSH_CONFIG_PATH" ]]; then
    transfer_cmd+=(--ssh-config "$SSH_CONFIG_PATH")
  fi
  for ssh_option in "${SSH_OPTIONS[@]-}"; do
    [[ -n "$ssh_option" ]] || continue
    transfer_cmd+=(--ssh-option "$ssh_option")
  done
  if [[ "$APPLY_IMPORT" == "true" ]]; then
    transfer_cmd+=(--apply-import)
  fi

  echo ""
  echo "[3/3] Promoting cached DB metadata to remote..."
  "${transfer_cmd[@]}"
else
  echo ""
  echo "[3/3] Skipped remote DB transfer."
fi

echo ""
echo "Workflow complete."
