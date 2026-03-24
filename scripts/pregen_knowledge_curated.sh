#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-5}"
MAX_WAIT_MINUTES_PER_BOOK="${MAX_WAIT_MINUTES_PER_BOOK:-0}"
OFFSET="${OFFSET:-0}"
LIMIT="${LIMIT:-0}"
CONTINUE_ON_ERROR=false
SKIP_RECAPS=false
SKIP_QUIZZES=false

CURATED_GUTENBERG_IDS=(
  1342 2701 84 345 11 1260 768 174 98 1184
  2554 1399 1661 1727 996 135 2600 28054 120 25
  1400 76 46 43 35 36 5230 2488 103 18857
  37106 17396 16 55 52521 215 219 940 1257 161
  105 141 121 145 766 730 1837 86 2852 175
)

usage() {
  cat <<EOF
Usage:
  scripts/pregen_knowledge_curated.sh [options]

Options:
  --api-base-url <url>               API base URL (default: ${API_BASE_URL}).
  --poll-interval-seconds <n>        Poll interval for recap/quiz status checks (default: ${POLL_INTERVAL_SECONDS}).
  --max-wait-minutes-per-book <n>    Max wait per book. Use 0 for auto timeout by chapter count (default: ${MAX_WAIT_MINUTES_PER_BOOK}).
  --offset <n>                       Skip the first n curated books (default: ${OFFSET}).
  --limit <n>                        Process at most n curated books after offset. Use 0 for all remaining (default: ${LIMIT}).
  --continue-on-error                Continue processing remaining books after a failure.
  --skip-recaps                      Pass through to the one-book script.
  --skip-quizzes                     Pass through to the one-book script.
  --help                             Show help.

Examples:
  scripts/pregen_knowledge_curated.sh --offset 20 --limit 30
  scripts/pregen_knowledge_curated.sh --limit 1
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
    --max-wait-minutes-per-book)
      [[ $# -ge 2 ]] || fail "--max-wait-minutes-per-book requires a value"
      MAX_WAIT_MINUTES_PER_BOOK="$2"
      shift 2
      ;;
    --offset)
      [[ $# -ge 2 ]] || fail "--offset requires a value"
      OFFSET="$2"
      shift 2
      ;;
    --limit)
      [[ $# -ge 2 ]] || fail "--limit requires a value"
      LIMIT="$2"
      shift 2
      ;;
    --continue-on-error)
      CONTINUE_ON_ERROR=true
      shift
      ;;
    --skip-recaps)
      SKIP_RECAPS=true
      shift
      ;;
    --skip-quizzes)
      SKIP_QUIZZES=true
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

[[ "$POLL_INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || fail "--poll-interval-seconds must be an integer"
[[ "$MAX_WAIT_MINUTES_PER_BOOK" =~ ^[0-9]+$ ]] || fail "--max-wait-minutes-per-book must be an integer"
[[ "$OFFSET" =~ ^[0-9]+$ ]] || fail "--offset must be an integer"
[[ "$LIMIT" =~ ^[0-9]+$ ]] || fail "--limit must be an integer"
[[ "$POLL_INTERVAL_SECONDS" -gt 0 ]] || fail "--poll-interval-seconds must be > 0"
[[ "$OFFSET" -le ${#CURATED_GUTENBERG_IDS[@]} ]] || fail "--offset exceeds curated catalog size (${#CURATED_GUTENBERG_IDS[@]})"

require_command curl
require_command jq

KNOWLEDGE_BOOK_SCRIPT="${SCRIPT_DIR}/pregen_knowledge_book.sh"
[[ -x "$KNOWLEDGE_BOOK_SCRIPT" ]] || fail "Missing executable script: ${KNOWLEDGE_BOOK_SCRIPT}"

selected_ids=("${CURATED_GUTENBERG_IDS[@]:OFFSET}")
if [[ "$LIMIT" -gt 0 && "$LIMIT" -lt ${#selected_ids[@]} ]]; then
  selected_ids=("${selected_ids[@]:0:LIMIT}")
fi

[[ ${#selected_ids[@]} -gt 0 ]] || fail "No curated books selected with offset=${OFFSET} limit=${LIMIT}"

echo "Curated knowledge pre-generation start"
echo "  API: ${API_BASE_URL}"
echo "  Total curated books: ${#CURATED_GUTENBERG_IDS[@]}"
echo "  Selected books: ${#selected_ids[@]}"
echo "  Offset: ${OFFSET}"
echo "  Limit: ${LIMIT}"

success_count=0
failure_count=0
processed_count=0

for gutenberg_id in "${selected_ids[@]}"; do
  processed_count=$((processed_count + 1))
  echo ""
  echo "=================================================="
  echo "Processing [${processed_count}/${#selected_ids[@]}] Gutenberg ID: ${gutenberg_id}"
  echo "=================================================="

  response_file="/tmp/pregen_knowledge_curated_import.$$"
  http_code="$(
    curl --silent --show-error \
      --output "${response_file}" \
      --write-out '%{http_code}' \
      -X POST "${API_BASE_URL}/api/import/gutenberg/${gutenberg_id}"
  )"

  body="$(cat "${response_file}" 2>/dev/null || true)"
  rm -f "${response_file}"

  case "$http_code" in
    200|409)
      ;;
    *)
      echo "Import failed for Gutenberg ID ${gutenberg_id}: HTTP ${http_code} ${body}" >&2
      failure_count=$((failure_count + 1))
      if [[ "$CONTINUE_ON_ERROR" == "true" ]]; then
        continue
      fi
      exit 2
      ;;
  esac

  book_id="$(printf '%s' "$body" | jq -r '.bookId // empty')"
  message="$(printf '%s' "$body" | jq -r '.message // "no-message"')"
  if [[ -z "$book_id" ]]; then
    echo "No bookId returned for Gutenberg ID ${gutenberg_id}. Body: ${body}" >&2
    failure_count=$((failure_count + 1))
    if [[ "$CONTINUE_ON_ERROR" == "true" ]]; then
      continue
    fi
    exit 2
  fi

  echo "Import status: HTTP ${http_code} (${message}), bookId=${book_id}"

  declare -a book_args=(
    --api-base-url "$API_BASE_URL"
    --book-id "$book_id"
    --poll-interval-seconds "$POLL_INTERVAL_SECONDS"
    --max-wait-minutes "$MAX_WAIT_MINUTES_PER_BOOK"
  )
  if [[ "$SKIP_RECAPS" == "true" ]]; then
    book_args+=(--skip-recaps)
  fi
  if [[ "$SKIP_QUIZZES" == "true" ]]; then
    book_args+=(--skip-quizzes)
  fi

  if "$KNOWLEDGE_BOOK_SCRIPT" "${book_args[@]}"; then
    success_count=$((success_count + 1))
  else
    echo "Knowledge pre-generation failed for Gutenberg ID ${gutenberg_id} (bookId=${book_id})" >&2
    failure_count=$((failure_count + 1))
    if [[ "$CONTINUE_ON_ERROR" == "true" ]]; then
      continue
    fi
    exit 2
  fi
done

echo ""
echo "Curated knowledge pre-generation summary"
echo "  Success: ${success_count}"
echo "  Failed: ${failure_count}"

if [[ "$failure_count" -gt 0 ]]; then
  exit 2
fi

exit 0
