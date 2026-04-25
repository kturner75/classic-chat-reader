#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

MAVEN_BIN="${MAVEN_BIN:-mvn}"
API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
SOURCE="${SOURCE:-gutenberg}"
GUTENBERG_ID=""
PREGEN_POLL_SECONDS="${PREGEN_POLL_SECONDS:-10}"
PREGEN_TIMEOUT_SECONDS="${PREGEN_TIMEOUT_SECONDS:-7200}"
PREGEN_JOB_ID=""

SKIP_PREGEN=false
SKIP_EXPORT=false
SYNC_ASSETS=false
SYNC_DIRECTION="${SYNC_DIRECTION:-up}"

EXPORT_DIR="${EXPORT_DIR:-${ROOT_DIR}/data/transfers}"
EXPORT_PATH=""

IMPORT_DB_URL=""
IMPORT_DB_USER="${IMPORT_DB_USER:-sa}"
IMPORT_DB_PASSWORD="${IMPORT_DB_PASSWORD:-}"
IMPORT_CONFLICT="${IMPORT_CONFLICT:-skip}"
IMPORT_APPLY=false

usage() {
  cat <<EOF
Usage:
  scripts/pregen_transfer_book.sh --gutenberg-id <id> [options]

Required:
  --gutenberg-id <id>              Project Gutenberg ID.

Options:
  --api-base-url <url>             Pre-generation API base URL (default: ${API_BASE_URL}).
  --pregen-poll-seconds <seconds>  Poll interval for async pre-generation status (default: ${PREGEN_POLL_SECONDS}).
  --pregen-timeout-seconds <sec>   Timeout before cancelling pre-generation (default: ${PREGEN_TIMEOUT_SECONDS}).
  --skip-pregen                    Skip API pre-generation step.
  --skip-export                    Skip recap export step.
  --export-path <path>             Output recap JSON path.
  --export-dir <path>              Export directory when --export-path is not set (default: ${EXPORT_DIR}).

  --import-db-url <jdbc-url>       Optional target DB URL for recap import.
  --import-db-user <user>          Target DB user (default: ${IMPORT_DB_USER}).
  --import-db-password <pass>      Target DB password (default: empty).
  --import-on-conflict <mode>      skip|overwrite (default: ${IMPORT_CONFLICT}).
  --import-apply                   Apply DB writes. Default is dry-run import only.

  --sync-assets                    Run scripts/sync_spaces.sh after export/import.
  --sync-direction <up|down>       Passed to sync script (default: ${SYNC_DIRECTION}).
  --help                           Show this message.

Notes:
  - Pre-generation starts a job via POST /api/pregen/jobs/gutenberg/{id}, then polls GET /api/pregen/jobs/{jobId}.
  - Ctrl+C (SIGINT) requests cancellation via POST /api/pregen/jobs/{jobId}/cancel.
  - Export writes recaps for --book-source-id <id> using CacheTransferRunner.
  - If --import-db-url is set, script runs import dry-run, and runs apply when --import-apply is present.
EOF
}

fail() {
  echo "Error: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
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
    --skip-pregen)
      SKIP_PREGEN=true
      shift
      ;;
    --skip-export)
      SKIP_EXPORT=true
      shift
      ;;
    --export-path)
      [[ $# -ge 2 ]] || fail "--export-path requires a value"
      EXPORT_PATH="$2"
      shift 2
      ;;
    --export-dir)
      [[ $# -ge 2 ]] || fail "--export-dir requires a value"
      EXPORT_DIR="$2"
      shift 2
      ;;
    --import-db-url)
      [[ $# -ge 2 ]] || fail "--import-db-url requires a value"
      IMPORT_DB_URL="$2"
      shift 2
      ;;
    --import-db-user)
      [[ $# -ge 2 ]] || fail "--import-db-user requires a value"
      IMPORT_DB_USER="$2"
      shift 2
      ;;
    --import-db-password)
      [[ $# -ge 2 ]] || fail "--import-db-password requires a value"
      IMPORT_DB_PASSWORD="$2"
      shift 2
      ;;
    --import-on-conflict)
      [[ $# -ge 2 ]] || fail "--import-on-conflict requires a value"
      IMPORT_CONFLICT="$2"
      shift 2
      ;;
    --import-apply)
      IMPORT_APPLY=true
      shift
      ;;
    --sync-assets)
      SYNC_ASSETS=true
      shift
      ;;
    --sync-direction)
      [[ $# -ge 2 ]] || fail "--sync-direction requires a value"
      SYNC_DIRECTION="$2"
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

[[ -n "${GUTENBERG_ID}" ]] || fail "--gutenberg-id is required"
[[ "${SOURCE}" == "gutenberg" ]] || fail "Only SOURCE=gutenberg is supported right now"
[[ "${IMPORT_CONFLICT}" == "skip" || "${IMPORT_CONFLICT}" == "overwrite" ]] || fail "--import-on-conflict must be skip or overwrite"
[[ "${SYNC_DIRECTION}" == "up" || "${SYNC_DIRECTION}" == "down" ]] || fail "--sync-direction must be up or down"
[[ "${PREGEN_POLL_SECONDS}" =~ ^[0-9]+$ ]] || fail "--pregen-poll-seconds must be a non-negative integer"
[[ "${PREGEN_TIMEOUT_SECONDS}" =~ ^[0-9]+$ ]] || fail "--pregen-timeout-seconds must be a non-negative integer"
(( PREGEN_TIMEOUT_SECONDS > 0 )) || fail "--pregen-timeout-seconds must be greater than zero"

require_command curl
require_command "${MAVEN_BIN}"
require_command jq

if [[ -z "${EXPORT_PATH}" ]]; then
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  EXPORT_PATH="${EXPORT_DIR}/recaps-${SOURCE}-${GUTENBERG_ID}-${ts}.json"
fi

run_cache_transfer() {
  local command="$1"
  shift
  "${MAVEN_BIN}" -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.classicchatreader.cli.CacheTransferRunner \
    -Dexec.args="${command} $*"
}

cancel_pregen_job() {
  if [[ -z "${PREGEN_JOB_ID}" ]]; then
    return 0
  fi
  curl --silent --show-error --fail \
    -X POST "${API_BASE_URL}/api/pregen/jobs/${PREGEN_JOB_ID}/cancel" >/dev/null || true
}

on_interrupt() {
  if [[ -n "${PREGEN_JOB_ID}" ]]; then
    echo ""
    echo "Interrupt received. Cancelling pre-generation job ${PREGEN_JOB_ID}..."
    cancel_pregen_job
  fi
  exit 130
}

trap on_interrupt INT TERM

start_pregen_job() {
  local response_file status_code body
  response_file="$(mktemp)"
  status_code="$(curl --silent --show-error --output "${response_file}" --write-out '%{http_code}' \
    -X POST "${API_BASE_URL}/api/pregen/jobs/gutenberg/${GUTENBERG_ID}")"
  body="$(cat "${response_file}" 2>/dev/null || true)"
  rm -f "${response_file}"

  if [[ "${status_code}" != "202" ]]; then
    echo "${body}" >&2
    fail "Failed to start pre-generation job (HTTP ${status_code})"
  fi

  PREGEN_JOB_ID="$(echo "${body}" | jq -r '.jobId // empty' 2>/dev/null || true)"
  [[ -n "${PREGEN_JOB_ID}" ]] || fail "Pre-generation start response did not include jobId"
  echo "Pre-generation job started: ${PREGEN_JOB_ID}"
}

wait_for_pregen_job() {
  local started_at now elapsed response_file status_code body
  local state message error pending generating failed success
  started_at="$(date +%s)"

  while true; do
    response_file="$(mktemp)"
    status_code="$(curl --silent --show-error --output "${response_file}" --write-out '%{http_code}' \
      "${API_BASE_URL}/api/pregen/jobs/${PREGEN_JOB_ID}")"
    body="$(cat "${response_file}" 2>/dev/null || true)"
    rm -f "${response_file}"

    if [[ "${status_code}" != "200" ]]; then
      echo "${body}" >&2
      fail "Failed to fetch pre-generation job status (HTTP ${status_code})"
    fi

    state="$(echo "${body}" | jq -r '.state // "UNKNOWN"' 2>/dev/null || true)"
    message="$(echo "${body}" | jq -r '.message // ""' 2>/dev/null || true)"
    error="$(echo "${body}" | jq -r '.error // ""' 2>/dev/null || true)"
    pending="$(echo "${body}" | jq -r '.progress.totals.pending // 0' 2>/dev/null || true)"
    generating="$(echo "${body}" | jq -r '.progress.totals.generating // 0' 2>/dev/null || true)"
    failed="$(echo "${body}" | jq -r '.progress.totals.failed // 0' 2>/dev/null || true)"
    elapsed="$(( $(date +%s) - started_at ))"

    echo "  [${elapsed}s] state=${state} pending=${pending} generating=${generating} failed=${failed}"

    case "${state}" in
      COMPLETED)
        success="$(echo "${body}" | jq -r '.result.success // false' 2>/dev/null || true)"
        if [[ "${success}" == "true" ]]; then
          PREGEN_JOB_ID=""
          echo "Pre-generation completed."
          return 0
        fi
        fail "Pre-generation completed with failures: ${message}"
        ;;
      FAILED)
        fail "Pre-generation failed: ${error:-$message}"
        ;;
      CANCELLED)
        fail "Pre-generation cancelled"
        ;;
      QUEUED|RUNNING|CANCELLING)
        ;;
      *)
        echo "Unexpected pre-generation state: ${state}" >&2
        ;;
    esac

    now="$(date +%s)"
    if (( now - started_at >= PREGEN_TIMEOUT_SECONDS )); then
      echo "Pre-generation timed out after ${PREGEN_TIMEOUT_SECONDS}s; requesting cancel..." >&2
      cancel_pregen_job
      fail "Pre-generation timed out"
    fi

    sleep "${PREGEN_POLL_SECONDS}"
  done
}

echo "Workflow start"
echo "  Gutenberg ID: ${GUTENBERG_ID}"
echo "  API base URL: ${API_BASE_URL}"
echo "  Export path : ${EXPORT_PATH}"

if [[ "${SKIP_PREGEN}" == "false" ]]; then
  echo ""
  echo "[1/4] Pre-generating illustrations, portraits, and recaps..."
  start_pregen_job
  wait_for_pregen_job
else
  echo ""
  echo "[1/4] Skipped pre-generation (--skip-pregen)"
fi

if [[ "${SKIP_EXPORT}" == "false" ]]; then
  echo ""
  echo "[2/4] Exporting recap transfer JSON..."
  mkdir -p "$(dirname "${EXPORT_PATH}")"
  run_cache_transfer export "--feature recaps --book-source-id ${GUTENBERG_ID} --apply --output \"${EXPORT_PATH}\""
  [[ -f "${EXPORT_PATH}" ]] || fail "Expected export file not found: ${EXPORT_PATH}"
  echo "Exported recap bundle: ${EXPORT_PATH}"
else
  echo ""
  echo "[2/4] Skipped export (--skip-export)"
fi

if [[ -n "${IMPORT_DB_URL}" ]]; then
  [[ -f "${EXPORT_PATH}" ]] || fail "Import requires recap export file at ${EXPORT_PATH}"

  echo ""
  echo "[3/4] Import dry-run into target DB..."
  run_cache_transfer import "--feature recaps --input \"${EXPORT_PATH}\" --on-conflict ${IMPORT_CONFLICT} --db-url \"${IMPORT_DB_URL}\" --db-user \"${IMPORT_DB_USER}\" --db-password \"${IMPORT_DB_PASSWORD}\""

  if [[ "${IMPORT_APPLY}" == "true" ]]; then
    echo "Applying import..."
    run_cache_transfer import "--feature recaps --input \"${EXPORT_PATH}\" --on-conflict ${IMPORT_CONFLICT} --apply --db-url \"${IMPORT_DB_URL}\" --db-user \"${IMPORT_DB_USER}\" --db-password \"${IMPORT_DB_PASSWORD}\""
  else
    echo "Skipped import apply (use --import-apply to write)."
  fi
else
  echo ""
  echo "[3/4] Skipped import (set --import-db-url to enable)."
fi

if [[ "${SYNC_ASSETS}" == "true" ]]; then
  echo ""
  echo "[4/4] Syncing assets via scripts/sync_spaces.sh (${SYNC_DIRECTION})..."
  SYNC_DIRECTION="${SYNC_DIRECTION}" DATA_DIR="${ROOT_DIR}/data" "${ROOT_DIR}/scripts/sync_spaces.sh"
else
  echo ""
  echo "[4/4] Skipped asset sync (use --sync-assets to enable)."
fi

echo ""
echo "Workflow complete."
