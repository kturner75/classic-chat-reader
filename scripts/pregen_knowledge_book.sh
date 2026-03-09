#!/usr/bin/env bash
set -euo pipefail

API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
BOOK_ID=""
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-5}"
MAX_WAIT_MINUTES="${MAX_WAIT_MINUTES:-0}"
AUTO_TIMEOUT_SECONDS_PER_CHAPTER="${AUTO_TIMEOUT_SECONDS_PER_CHAPTER:-30}"
AUTO_TIMEOUT_BUFFER_MINUTES="${AUTO_TIMEOUT_BUFFER_MINUTES:-30}"
SKIP_RECAPS="false"
SKIP_QUIZZES="false"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<EOF
Usage:
  scripts/pregen_knowledge_book.sh --book-id <book-id> [options]

Required:
  --book-id <book-id>              Local book ID from /api/library.

Options:
  --api-base-url <url>             API base URL (default: ${API_BASE_URL}).
  --poll-interval-seconds <n>      Poll interval (default: ${POLL_INTERVAL_SECONDS}).
  --max-wait-minutes <n>           Max wait before timeout. Use 0 for auto timeout by chapter count (default: ${MAX_WAIT_MINUTES}).
  --skip-recaps                    Skip recap generation.
  --skip-quizzes                   Skip quiz generation.
  --help                           Show help.

Notes:
  - This script generates chapter recaps first, then quizzes.
  - It is safe to rerun; completed chapters are treated as already ready.
  - Exit code is non-zero if recap or quiz generation fails or times out.
EOF
}

fail() {
  echo "Error: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

parse_retry_after_seconds() {
  local header_file="$1"
  local body_file="$2"
  local retry_after=""
  retry_after="$(awk 'BEGIN{IGNORECASE=1} /^Retry-After:/ {gsub("\r","",$2); print $2; exit}' "${header_file}" 2>/dev/null || true)"
  if [[ -n "${retry_after}" && "${retry_after}" =~ ^[0-9]+$ ]]; then
    echo "${retry_after}"
    return
  fi
  local body_retry=""
  body_retry="$(jq -r '.windowSeconds // empty' "${body_file}" 2>/dev/null || true)"
  if [[ -n "${body_retry}" && "${body_retry}" =~ ^[0-9]+$ ]]; then
    echo "${body_retry}"
    return
  fi
  echo 60
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --book-id)
      [[ $# -ge 2 ]] || fail "--book-id requires a value"
      BOOK_ID="$2"
      shift 2
      ;;
    --api-base-url)
      [[ $# -ge 2 ]] || fail "--api-base-url requires a value"
      API_BASE_URL="$2"
      shift 2
      ;;
    --poll-interval-seconds)
      [[ $# -ge 2 ]] || fail "--poll-interval-seconds requires a value"
      POLL_INTERVAL_SECONDS="$2"
      shift 2
      ;;
    --max-wait-minutes)
      [[ $# -ge 2 ]] || fail "--max-wait-minutes requires a value"
      MAX_WAIT_MINUTES="$2"
      shift 2
      ;;
    --skip-recaps)
      SKIP_RECAPS="true"
      shift
      ;;
    --skip-quizzes)
      SKIP_QUIZZES="true"
      shift
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

[[ -n "$BOOK_ID" ]] || fail "--book-id is required"
[[ "$POLL_INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || fail "--poll-interval-seconds must be an integer"
[[ "$MAX_WAIT_MINUTES" =~ ^[0-9]+$ ]] || fail "--max-wait-minutes must be an integer"
[[ "$AUTO_TIMEOUT_SECONDS_PER_CHAPTER" =~ ^[0-9]+$ ]] || fail "AUTO_TIMEOUT_SECONDS_PER_CHAPTER must be an integer"
[[ "$AUTO_TIMEOUT_BUFFER_MINUTES" =~ ^[0-9]+$ ]] || fail "AUTO_TIMEOUT_BUFFER_MINUTES must be an integer"
[[ "$POLL_INTERVAL_SECONDS" -gt 0 ]] || fail "--poll-interval-seconds must be > 0"

require_command curl
require_command jq

BOOK_JSON="$(curl --fail --silent --show-error "${API_BASE_URL}/api/library/${BOOK_ID}")" || {
  fail "Failed to fetch book from ${API_BASE_URL}/api/library/${BOOK_ID}"
}

BOOK_TITLE="$(printf '%s' "$BOOK_JSON" | jq -r '.title // "Unknown"')"
CHAPTER_IDS=()
while IFS= read -r chapter_id; do
  [[ -n "$chapter_id" ]] || continue
  CHAPTER_IDS+=("$chapter_id")
done < <(printf '%s' "$BOOK_JSON" | jq -r '.chapters[]?.id')

if [[ ${#CHAPTER_IDS[@]} -eq 0 ]]; then
  fail "No chapters found for book ${BOOK_ID}."
fi

effective_max_wait_minutes="$MAX_WAIT_MINUTES"
if [[ "$effective_max_wait_minutes" -eq 0 ]]; then
  estimated_minutes=$(( (${#CHAPTER_IDS[@]} * AUTO_TIMEOUT_SECONDS_PER_CHAPTER + 59) / 60 ))
  effective_max_wait_minutes=$(( estimated_minutes + AUTO_TIMEOUT_BUFFER_MINUTES ))
  if [[ "$effective_max_wait_minutes" -lt 60 ]]; then
    effective_max_wait_minutes=60
  fi
fi

print_header() {
  echo "Knowledge pre-generation start"
  echo "  Book: ${BOOK_TITLE} (${BOOK_ID})"
  echo "  Chapters: ${#CHAPTER_IDS[@]}"
  echo "  API: ${API_BASE_URL}"
  echo "  Max wait (minutes): ${effective_max_wait_minutes}"
  echo "  Recaps: ${SKIP_RECAPS}"
  echo "  Quizzes: ${SKIP_QUIZZES}"
}

queue_recaps() {
  local queued=0
  local already_ready=0

  for chapter_id in "${CHAPTER_IDS[@]}"; do
    local status_json status
    status_json="$(curl --fail --silent --show-error "${API_BASE_URL}/api/recaps/chapter/${chapter_id}/status")" || {
      fail "Failed to fetch recap status for chapter ${chapter_id}"
    }
    status="$(printf '%s' "$status_json" | jq -r '.status // "MISSING"')"
    if [[ "$status" == "COMPLETED" ]]; then
      already_ready=$((already_ready + 1))
      continue
    fi

    while true; do
      local header_file="/tmp/pregen_recap_headers.$$"
      local body_file="/tmp/pregen_recap_resp.$$"
      local status_code
      status_code="$(curl --silent --output "${body_file}" --dump-header "${header_file}" --write-out '%{http_code}' \
        -X POST "${API_BASE_URL}/api/recaps/chapter/${chapter_id}/generate")"
      case "$status_code" in
        202)
          queued=$((queued + 1))
          rm -f "${header_file}" "${body_file}"
          break
          ;;
        403)
          rm -f "${header_file}" "${body_file}"
          fail "Recap generation unavailable (HTTP 403). Check recap/reasoning feature flags."
          ;;
        404)
          rm -f "${header_file}" "${body_file}"
          fail "Chapter not found while queueing recap: ${chapter_id}"
          ;;
        409)
          rm -f "${header_file}" "${body_file}"
          fail "Recap generation blocked in cache-only mode (HTTP 409)."
          ;;
        429)
          local retry_after
          retry_after="$(parse_retry_after_seconds "${header_file}" "${body_file}")"
          echo "Recap queue rate-limited for chapter ${chapter_id}; sleeping ${retry_after}s before retry..."
          rm -f "${header_file}" "${body_file}"
          sleep "${retry_after}"
          ;;
        *)
          local response_body
          response_body="$(cat "${body_file}" 2>/dev/null || true)"
          rm -f "${header_file}" "${body_file}"
          fail "Unexpected recap generate response for chapter ${chapter_id}: HTTP ${status_code} ${response_body}"
          ;;
      esac
    done
  done
  rm -f /tmp/pregen_recap_headers.$$ /tmp/pregen_recap_resp.$$

  echo "Recap queue complete"
  echo "  Already completed: ${already_ready}"
  echo "  Queued: ${queued}"
}

wait_for_recaps() {
  local start_epoch deadline_epoch last_progress=""
  start_epoch="$(date +%s)"
  deadline_epoch=$((start_epoch + effective_max_wait_minutes * 60))

  while true; do
    local completed=0 failed=0 pending=0 generating=0 missing=0

    for chapter_id in "${CHAPTER_IDS[@]}"; do
      local status_json status
      status_json="$(curl --fail --silent --show-error "${API_BASE_URL}/api/recaps/chapter/${chapter_id}/status")" || {
        fail "Failed to fetch recap status for chapter ${chapter_id}"
      }
      status="$(printf '%s' "$status_json" | jq -r '.status // "MISSING"')"
      case "$status" in
        COMPLETED) completed=$((completed + 1)) ;;
        FAILED) failed=$((failed + 1)) ;;
        GENERATING) generating=$((generating + 1)) ;;
        PENDING) pending=$((pending + 1)) ;;
        MISSING) missing=$((missing + 1)) ;;
        *) pending=$((pending + 1)) ;;
      esac
    done

    local progress="completed=${completed} generating=${generating} pending=${pending} missing=${missing} failed=${failed}"
    if [[ "$progress" != "$last_progress" ]]; then
      echo "Recap progress: ${progress}"
      last_progress="$progress"
    fi

    if [[ $((completed + failed)) -eq ${#CHAPTER_IDS[@]} ]]; then
      echo "Recap pre-generation complete"
      echo "  Completed: ${completed}"
      echo "  Failed: ${failed}"
      if [[ "$failed" -gt 0 ]]; then
        exit 2
      fi
      return
    fi

    local now_epoch
    now_epoch="$(date +%s)"
    if [[ "$now_epoch" -ge "$deadline_epoch" ]]; then
      fail "Timed out waiting for recap generation after ${effective_max_wait_minutes} minutes."
    fi

    sleep "$POLL_INTERVAL_SECONDS"
  done
}

print_header

if [[ "$SKIP_RECAPS" == "false" ]]; then
  echo ""
  echo "[1/2] Generating recaps..."
  queue_recaps
  wait_for_recaps
else
  echo ""
  echo "[1/2] Skipped recap generation."
fi

if [[ "$SKIP_QUIZZES" == "false" ]]; then
  echo ""
  echo "[2/2] Generating quizzes..."
  API_BASE_URL="${API_BASE_URL}" \
  POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS}" \
  MAX_WAIT_MINUTES="${effective_max_wait_minutes}" \
  "${SCRIPT_DIR}/pregen_quizzes_book.sh" --book-id "${BOOK_ID}" --api-base-url "${API_BASE_URL}" \
    --poll-interval-seconds "${POLL_INTERVAL_SECONDS}" --max-wait-minutes "${effective_max_wait_minutes}"
else
  echo ""
  echo "[2/2] Skipped quiz generation."
fi

echo ""
echo "Knowledge pre-generation complete."
