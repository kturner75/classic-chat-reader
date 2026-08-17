#!/usr/bin/env bash
# Re-derive characters for the BL-052 short works after the extraction/prefetch fixes.
#
# PREREQUISITE: the server on --api-base-url must be running code that includes the
# CharacterExtractionService + CharacterPrefetchService fixes. Running this against the
# old build regenerates with the 3000-char limit AND re-latches character_prefetch_completed
# back to true on every book, undoing the flag reset.
#
# Per book: DELETE clears characters, chapter_analyses, and portrait files, so the pregen
# job re-runs prefetch (step 3) and analysis for every chapter (step 4) from scratch.
set -euo pipefail

ROOT_DIR="/Users/kevinturner/IdeaProjects/classic-chat-reader"
API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
DRY_RUN="${DRY_RUN:-false}"

# gutenberg_id:book_id  — BL-052 curated short fiction / drama / poetry
BOOKS=(
  "1063:8167f57f-f088-4726-aa50-d63971d1b15d"   # The Cask of Amontillado
  "3189:763ff069-2af7-423e-b2aa-adb439243fed"   # Sketches New and Old
  "512:b7dd0031-e5f3-4427-a5c9-915550b1073b"    # Mosses from an Old Manse
  "10623:e3065bc8-0feb-451c-b980-b06e1fbf6d57"  # Plays (Glaspell)
  "1041:42d10cee-9ffc-491c-8e69-a2fccf249ca0"   # Shakespeare's Sonnets
  "8601:e4b8c778-38d0-4be9-9ec2-acfb30626c3e"   # Early Poems of Tennyson
  "16376:ef70ff90-16d1-4b74-b71c-fbbaf0374e15"  # Browning's Shorter Poems
  "12242:ee35e1f2-8bc5-4087-8451-9c711beed58d"  # Emily Dickinson
  "1459:dbb17695-3185-4eff-8ca8-c5485825f0b9"   # Prufrock and Other Observations
)

command -v jq >/dev/null || { echo "Error: jq required" >&2; exit 1; }

echo "API: ${API_BASE_URL}   dry-run: ${DRY_RUN}"
echo

for entry in "${BOOKS[@]}"; do
  gid="${entry%%:*}"
  book_id="${entry##*:}"

  echo "=== Gutenberg ${gid} (${book_id}) ==="

  if [[ "${DRY_RUN}" == "true" ]]; then
    echo "  [dry-run] would DELETE ${API_BASE_URL}/api/characters/book/${book_id}"
    echo "  [dry-run] would pregen ${gid}"
    continue
  fi

  echo "--- clearing existing characters, analyses, and portrait files"
  curl --fail --silent --show-error -X DELETE \
    "${API_BASE_URL}/api/characters/book/${book_id}" | jq -r '.message // .'

  echo "--- regenerating (prefetch + per-chapter analysis + portraits)"
  ( cd "${ROOT_DIR}" && scripts/pregen_transfer_book.sh \
      --gutenberg-id "${gid}" \
      --api-base-url "${API_BASE_URL}" \
      --skip-export )

  echo
done

echo "=== Result ==="
psql "postgresql://localhost:5432/public_domain_reader" -c "
select b.source_id, b.title, b.character_prefetch_completed as prefetch_flag,
       count(c.id) filter (where c.character_type='PRIMARY')   as primary_ct,
       count(c.id) filter (where c.character_type='SECONDARY') as secondary_ct
from books b left join characters c on c.book_id = b.id
where b.source_id in ('1063','3189','512','10623','1041','8601','16376','12242','1459')
group by b.id, b.source_id, b.title
order by b.source_id;"
