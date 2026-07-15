#!/usr/bin/env bash
set -euo pipefail

# Create a verified, portable logical backup of the production PostgreSQL database.
# Connection credentials are read over SSH from the protected production env file and
# exported only into this process. They are never printed or written to disk.

SSH_TARGET="${SSH_TARGET:-pdr}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/kevin}"
REMOTE_ENV_PATH="${REMOTE_ENV_PATH:-/opt/public-domain-reader/app.env}"
OUTPUT_DIR="${PDR_BACKUP_DIR:-$HOME/Backups/classic-chat-reader}"
BACKUP_LABEL="production"
PG_BIN="${PG_BIN:-}"

usage() {
  cat <<EOF
Usage:
  scripts/backup_production_db.sh [options]

Options:
  --ssh-target <host-or-alias>  SSH target used to read protected DB settings (default: ${SSH_TARGET})
  --ssh-key <path>              SSH private key (default: ${SSH_KEY})
  --remote-env <path>           Remote application env file (default: ${REMOTE_ENV_PATH})
  --output-dir <path>           Local backup directory (default: ${OUTPUT_DIR})
  --label <name>                Filename label (default: ${BACKUP_LABEL})
  --pg-bin <path>               Directory containing pg_dump, pg_restore, and psql
  --help                        Show this help

The PostgreSQL client major version must be at least the production server major
version. On macOS with Homebrew, install the matching client with:
  brew install postgresql@18
EOF
}

fail() {
  echo "Error: $*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ssh-target)
      [[ $# -ge 2 ]] || fail "--ssh-target requires a value"
      SSH_TARGET="$2"
      shift 2
      ;;
    --ssh-key)
      [[ $# -ge 2 ]] || fail "--ssh-key requires a value"
      SSH_KEY="$2"
      shift 2
      ;;
    --remote-env)
      [[ $# -ge 2 ]] || fail "--remote-env requires a value"
      REMOTE_ENV_PATH="$2"
      shift 2
      ;;
    --output-dir)
      [[ $# -ge 2 ]] || fail "--output-dir requires a value"
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --label)
      [[ $# -ge 2 ]] || fail "--label requires a value"
      BACKUP_LABEL="$2"
      shift 2
      ;;
    --pg-bin)
      [[ $# -ge 2 ]] || fail "--pg-bin requires a value"
      PG_BIN="$2"
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

[[ "$BACKUP_LABEL" =~ ^[A-Za-z0-9._-]+$ ]] || fail "--label may contain only letters, numbers, dot, underscore, and hyphen"
command -v ssh >/dev/null 2>&1 || fail "Missing required command: ssh"
[[ -z "$SSH_KEY" || -f "$SSH_KEY" ]] || fail "SSH key not found: $SSH_KEY"

if [[ -z "$PG_BIN" ]] && command -v brew >/dev/null 2>&1; then
  HOMEBREW_PG18="$(brew --prefix postgresql@18 2>/dev/null || true)/bin"
  if [[ -x "$HOMEBREW_PG18/pg_dump" ]]; then
    PG_BIN="$HOMEBREW_PG18"
  fi
fi
if [[ -z "$PG_BIN" ]]; then
  PG_DUMP_PATH="$(command -v pg_dump || true)"
  [[ -n "$PG_DUMP_PATH" ]] && PG_BIN="$(dirname "$PG_DUMP_PATH")"
fi

for tool in pg_dump pg_restore psql; do
  [[ -x "$PG_BIN/$tool" ]] || fail "Missing $tool in ${PG_BIN:-<unset>}; use --pg-bin with a matching PostgreSQL client"
done

SSH_ARGS=(-o BatchMode=yes)
if [[ -n "$SSH_KEY" ]]; then
  SSH_ARGS+=(-i "$SSH_KEY")
fi

echo "Reading production database connection settings over SSH..."
CONNECTION_EXPORTS="$(ssh "${SSH_ARGS[@]}" "$SSH_TARGET" bash -s -- "$REMOTE_ENV_PATH" <<'REMOTE_SCRIPT'
set -euo pipefail
env_file="$1"
[[ -r "$env_file" ]] || { echo "Remote env file is not readable: $env_file" >&2; exit 1; }
set -a
. "$env_file"
set +a

datasource_url="${SPRING_DATASOURCE_URL:-${PDR_DATASOURCE_URL:-${PDR_DATABASE_URL:-}}}"
database_user="${SPRING_DATASOURCE_USERNAME:-${PDR_DATASOURCE_USERNAME:-${PDR_DATABASE_USERNAME:-}}}"
database_password="${SPRING_DATASOURCE_PASSWORD:-${PDR_DATASOURCE_PASSWORD:-${PDR_DATABASE_PASSWORD:-}}}"

[[ "$datasource_url" == jdbc:postgresql://* ]] || { echo "Production datasource is not a PostgreSQL JDBC URL." >&2; exit 1; }
[[ -n "$database_user" ]] || { echo "Production datasource username is missing." >&2; exit 1; }
[[ -n "$database_password" ]] || { echo "Production datasource password is missing." >&2; exit 1; }

url="${datasource_url#jdbc:postgresql://}"
authority="${url%%/*}"
database="${url#*/}"
database="${database%%\?*}"
host="${authority%%:*}"
if [[ "$authority" == *:* ]]; then
  port="${authority##*:}"
else
  port="5432"
fi

printf 'export PGHOST=%q\n' "$host"
printf 'export PGPORT=%q\n' "$port"
printf 'export PGDATABASE=%q\n' "$database"
printf 'export PGUSER=%q\n' "$database_user"
printf 'export PGPASSWORD=%q\n' "$database_password"
printf 'export PGSSLMODE=require\n'
REMOTE_SCRIPT
)"

# The remote script shell-quotes every value with printf %q before it reaches eval.
eval "$CONNECTION_EXPORTS"
unset CONNECTION_EXPORTS

PARTIAL_FILE=""
cleanup() {
  unset PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD PGSSLMODE
  if [[ -n "$PARTIAL_FILE" && -f "$PARTIAL_FILE" ]]; then
    rm -f "$PARTIAL_FILE"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

SERVER_VERSION_NUM="$($PG_BIN/psql -X -A -t -v ON_ERROR_STOP=1 -c "SHOW server_version_num")"
SERVER_MAJOR=$((SERVER_VERSION_NUM / 10000))
CLIENT_VERSION="$($PG_BIN/pg_dump --version | awk '{for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+(\.[0-9]+)+$/) {print $i; exit}}')"
CLIENT_MAJOR="${CLIENT_VERSION%%.*}"
[[ "$CLIENT_MAJOR" =~ ^[0-9]+$ ]] || fail "Could not determine pg_dump major version from: $CLIENT_VERSION"
if (( CLIENT_MAJOR < SERVER_MAJOR )); then
  fail "pg_dump $CLIENT_VERSION cannot dump PostgreSQL server major $SERVER_MAJOR; install a matching or newer client"
fi

DATABASE_SIZE="$($PG_BIN/psql -X -A -t -v ON_ERROR_STOP=1 -c "SELECT pg_size_pretty(pg_database_size(current_database()))")"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUTPUT_DIR"
chmod 700 "$OUTPUT_DIR"
FINAL_FILE="$OUTPUT_DIR/${BACKUP_LABEL}-${TIMESTAMP}.dump"
PARTIAL_FILE="$FINAL_FILE.partial"

umask 077
echo "Creating consistent custom-format backup of database '$PGDATABASE' (${DATABASE_SIZE}, PostgreSQL ${SERVER_MAJOR})..."
"$PG_BIN/pg_dump" \
  --format=custom \
  --compress=6 \
  --no-owner \
  --no-privileges \
  --lock-wait-timeout=10s \
  --file="$PARTIAL_FILE"

echo "Verifying backup archive..."
ARCHIVE_LIST="$($PG_BIN/pg_restore --list "$PARTIAL_FILE")"
[[ -n "$ARCHIVE_LIST" ]] || fail "pg_restore could not read any archive entries"
grep -q 'TABLE DATA' <<< "$ARCHIVE_LIST" || fail "Backup contains no table data entries"

mv "$PARTIAL_FILE" "$FINAL_FILE"
PARTIAL_FILE=""
chmod 600 "$FINAL_FILE"

CHECKSUM_FILE="$FINAL_FILE.sha256"
(
  cd "$OUTPUT_DIR"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$(basename "$FINAL_FILE")" > "$(basename "$CHECKSUM_FILE")"
  else
    shasum -a 256 "$(basename "$FINAL_FILE")" > "$(basename "$CHECKSUM_FILE")"
  fi
)
chmod 600 "$CHECKSUM_FILE"

echo "Backup verified successfully."
echo "  archive : $FINAL_FILE"
echo "  checksum: $CHECKSUM_FILE"
echo "  client  : pg_dump $CLIENT_VERSION"
