#!/usr/bin/env bash
cd /Users/kevinturner/IdeaProjects/classic-chat-reader

NEW_GUTENBERG_IDS=(
  45 23 829 69087 3268 67098 12 203 583 696 289 4276
  22120 932 6124 77 610 6053 2787 2265 56621 70841 66084 64636 778
)

API_BASE_URL="http://localhost:8080"

for gid in "${NEW_GUTENBERG_IDS[@]}"; do
  echo
  echo "=== Importing Gutenberg ${gid} ==="

  import_json="$(curl --fail --silent --show-error -X POST "${API_BASE_URL}/api/import/gutenberg/${gid}")"
  book_id="$(printf '%s' "$import_json" | jq -r '.bookId')"

  echo "bookId=${book_id}"

  echo "=== Generating illustrations, portraits, and chapter summaries for ${gid} ==="
  scripts/pregen_transfer_book.sh \
    --gutenberg-id "${gid}" \
    --api-base-url "${API_BASE_URL}" \
    --skip-export

  echo "=== Generating quizzes for bookId ${book_id} ==="
  scripts/pregen_quizzes_book.sh \
    --book-id "${book_id}" \
    --api-base-url "${API_BASE_URL}"
done

# Upload generated illustration/portrait files to Spaces/CDN
SYNC_DIRECTION=up scripts/sync_spaces.sh

