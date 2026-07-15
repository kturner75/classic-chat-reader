#!/usr/bin/env bash
set -euo pipefail

# Grant, revoke, or inspect the durable CREATE_CLASSROOM account capability by email.
# Production DB credentials are read over SSH and kept only in this process environment.

SSH_TARGET="${SSH_TARGET:-pdr}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/kevin}"
REMOTE_ENV_PATH="${REMOTE_ENV_PATH:-/opt/public-domain-reader/app.env}"
PG_BIN="${PG_BIN:-}"

usage() {
  cat <<EOF
Usage:
  scripts/manage_teacher_access.sh <grant|revoke|status> <account-email> [options]

Options:
  --ssh-target <host-or-alias>  SSH target used to read protected DB settings (default: ${SSH_TARGET})
  --ssh-key <path>              SSH private key (default: ${SSH_KEY})
  --remote-env <path>           Remote application env file (default: ${REMOTE_ENV_PATH})
  --pg-bin <path>               Directory containing PostgreSQL 18 psql
  --help                        Show this help

The account must already exist. Granting access creates or reactivates the durable
CREATE_CLASSROOM capability; revoking it does not remove existing term memberships.
EOF
}

fail() {
  echo "Error: $*" >&2
  exit 1
}

[[ $# -ge 1 ]] || { usage; exit 1; }
if [[ "$1" == "--help" ]]; then
  usage
  exit 0
fi
[[ $# -ge 2 ]] || { usage; exit 1; }

ACTION="$1"
ACCOUNT_EMAIL="$2"
shift 2
[[ "$ACTION" == "grant" || "$ACTION" == "revoke" || "$ACTION" == "status" ]] \
  || fail "Action must be grant, revoke, or status"
[[ "$ACCOUNT_EMAIL" == *@* ]] || fail "A valid account email is required"

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

command -v ssh >/dev/null 2>&1 || fail "Missing required command: ssh"
[[ -z "$SSH_KEY" || -f "$SSH_KEY" ]] || fail "SSH key not found: $SSH_KEY"

if [[ -z "$PG_BIN" ]] && command -v brew >/dev/null 2>&1; then
  HOMEBREW_PG18="$(brew --prefix postgresql@18 2>/dev/null || true)/bin"
  if [[ -x "$HOMEBREW_PG18/psql" ]]; then
    PG_BIN="$HOMEBREW_PG18"
  fi
fi
if [[ -z "$PG_BIN" ]]; then
  PSQL_PATH="$(command -v psql || true)"
  [[ -n "$PSQL_PATH" ]] && PG_BIN="$(dirname "$PSQL_PATH")"
fi
[[ -x "$PG_BIN/psql" ]] || fail "Missing psql in ${PG_BIN:-<unset>}; use --pg-bin with PostgreSQL 18 client tools"

SSH_ARGS=(-o BatchMode=yes)
if [[ -n "$SSH_KEY" ]]; then
  SSH_ARGS+=(-i "$SSH_KEY")
fi

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

[[ "$datasource_url" == jdbc:postgresql://* ]] || { echo "Production datasource is not PostgreSQL." >&2; exit 1; }
[[ -n "$database_user" && -n "$database_password" ]] || { echo "Production datasource credentials are missing." >&2; exit 1; }

url="${datasource_url#jdbc:postgresql://}"
authority="${url%%/*}"
database="${url#*/}"
database="${database%%\?*}"
host="${authority%%:*}"
if [[ "$authority" == *:* ]]; then port="${authority##*:}"; else port="5432"; fi

printf 'export PGHOST=%q\n' "$host"
printf 'export PGPORT=%q\n' "$port"
printf 'export PGDATABASE=%q\n' "$database"
printf 'export PGUSER=%q\n' "$database_user"
printf 'export PGPASSWORD=%q\n' "$database_password"
printf 'export PGSSLMODE=require\n'
REMOTE_SCRIPT
)"

eval "$CONNECTION_EXPORTS"
unset CONNECTION_EXPORTS
cleanup() {
  unset PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD PGSSLMODE
}
trap cleanup EXIT

PSQL=("$PG_BIN/psql" -X -A -t -v ON_ERROR_STOP=1 -v "account_email=$ACCOUNT_EMAIL")

case "$ACTION" in
  grant)
    RESULT="$("${PSQL[@]}" <<'SQL'
WITH target_user AS (
    SELECT id FROM users WHERE LOWER(email) = LOWER(:'account_email')
), upserted AS (
    INSERT INTO account_capabilities (
        id, user_id, capability, status, granted_at, updated_at, revoked_at
    )
    SELECT gen_random_uuid()::text, id, 'CREATE_CLASSROOM', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
    FROM target_user
    ON CONFLICT (user_id, capability) DO UPDATE SET
        status = 'ACTIVE',
        granted_at = CURRENT_TIMESTAMP,
        updated_at = CURRENT_TIMESTAMP,
        revoked_at = NULL
    RETURNING user_id
)
SELECT COUNT(*) FROM upserted;
SQL
)"
    [[ "$RESULT" == "1" ]] || fail "No existing account found for $ACCOUNT_EMAIL"
    echo "Teacher classroom-creation access granted to $ACCOUNT_EMAIL."
    ;;
  revoke)
    RESULT="$("${PSQL[@]}" <<'SQL'
WITH updated AS (
    UPDATE account_capabilities
    SET status = 'REVOKED', updated_at = CURRENT_TIMESTAMP, revoked_at = CURRENT_TIMESTAMP
    WHERE capability = 'CREATE_CLASSROOM'
      AND user_id = (SELECT id FROM users WHERE LOWER(email) = LOWER(:'account_email'))
    RETURNING user_id
)
SELECT COUNT(*) FROM updated;
SQL
)"
    [[ "$RESULT" == "1" ]] || fail "No classroom-creation capability found for $ACCOUNT_EMAIL"
    echo "Teacher classroom-creation access revoked for $ACCOUNT_EMAIL."
    ;;
  status)
    RESULT="$("${PSQL[@]}" <<'SQL'
SELECT COALESCE(
    (SELECT ac.status
     FROM account_capabilities ac
     JOIN users u ON u.id = ac.user_id
     WHERE LOWER(u.email) = LOWER(:'account_email')
       AND ac.capability = 'CREATE_CLASSROOM'),
    'NOT_GRANTED'
);
SQL
)"
    echo "$ACCOUNT_EMAIL: $RESULT"
    ;;
esac
