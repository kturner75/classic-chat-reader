#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

MAVEN_BIN="${MAVEN_BIN:-mvn}"
FEATURE="recaps"
SOURCE_IDS=""
ALL_CACHED=false
REMOTE_RUNNER="${REMOTE_RUNNER:-auto}" # auto|maven|jar
REMOTE_JAVA_BIN="${REMOTE_JAVA_BIN:-java}"

LOCAL_DB_URL=""
LOCAL_DB_USER="${LOCAL_DB_USER:-sa}"
LOCAL_DB_PASSWORD="${LOCAL_DB_PASSWORD:-}"

REMOTE_TARGET=""
REMOTE_PROJECT_DIR=""
REMOTE_DB_URL=""
REMOTE_DB_USER="${REMOTE_DB_USER:-sa}"
REMOTE_DB_PASSWORD="${REMOTE_DB_PASSWORD:-}"
REMOTE_IMPORT_PATH="${REMOTE_IMPORT_PATH:-}"
REMOTE_JAR_PATH=""
REMOTE_HOME_DIR=""
ON_CONFLICT="${ON_CONFLICT:-skip}"
APPLY_IMPORT=false
REMOTE_STOP_CMD=""
REMOTE_START_CMD=""

EXPORT_DIR="${EXPORT_DIR:-${ROOT_DIR}/data/transfers}"
EXPORT_PATH=""
SSH_CONFIG_PATH=""
declare -a SSH_OPTIONS=()
declare -a SSH_CMD=(ssh)
declare -a SCP_CMD=(scp)

usage() {
  cat <<EOF
Usage:
  scripts/transfer_recaps_remote.sh [selection] --remote <host-or-alias> --remote-project-dir <path> --remote-db-url <jdbc-url> [options]

Selection (choose one):
  --book-source-id <id>[,<id>...]  Export specific source IDs.
  --all-cached                     Export all books with cached rows for selected feature(s).

Required:
  --remote <host-or-alias>         SSH target for import (supports ~/.ssh/config aliases).
  --remote-project-dir <path>      Project path on remote host.
  --remote-db-url <jdbc-url>       Remote DB URL for import.

Options:
  --feature <recaps|quizzes|illustrations|portraits|all>
                                  Transfer feature set (default: ${FEATURE}).
  --export-path <path>             Local transfer JSON path (single-feature only).
  --export-dir <path>              Used when --export-path is omitted (default: ${EXPORT_DIR}).

  --local-db-url <jdbc-url>        Local DB URL for export.
  --local-db-user <user>           Local DB user (default: ${LOCAL_DB_USER}).
  --local-db-password <pass>       Local DB password (default: empty).

  --remote-db-user <user>          Remote DB user (default: ${REMOTE_DB_USER}).
  --remote-db-password <pass>      Remote DB password (default: empty).
  --remote-import-path <path>      Remote JSON path (single feature), or base path for --feature all.
  --remote-runner <auto|maven|jar> Remote runner strategy (default: ${REMOTE_RUNNER}).
  --remote-jar-path <path>         Remote jar for runner=jar (default: <remote-project-dir>/target/classic-chat-reader-1.0-SNAPSHOT.jar).
  --remote-java-bin <bin>          Java binary for runner=jar (default: ${REMOTE_JAVA_BIN}).

  --on-conflict <skip|overwrite>   Import policy (default: ${ON_CONFLICT}).
  --apply-import                   Apply remote DB writes (default: dry-run only).
  --remote-stop-cmd "<cmd>"        Optional command run before apply import.
  --remote-start-cmd "<cmd>"       Optional command run after apply import.
  --ssh-config <path>              Optional SSH config file path.
  --ssh-option <k=v>               Extra SSH -o option (repeatable).
  --maven-bin <bin>                Maven executable (default: ${MAVEN_BIN}).
  --help                           Show this help.

Examples:
  scripts/transfer_recaps_remote.sh --book-source-id 1342 --remote reader-prod \\
    --remote-project-dir /opt/classic-chat-reader --remote-db-url "jdbc:h2:file:/opt/classic-chat-reader/data/library;DB_CLOSE_DELAY=-1"

  scripts/transfer_recaps_remote.sh --all-cached --remote reader-prod \\
    --remote-project-dir /opt/classic-chat-reader --remote-db-url "jdbc:h2:file:/opt/classic-chat-reader/data/library;DB_CLOSE_DELAY=-1" \\
    --apply-import --remote-stop-cmd "sudo systemctl stop classic-chat-reader" \\
    --remote-start-cmd "sudo systemctl start classic-chat-reader"

  scripts/transfer_recaps_remote.sh --book-source-id 1342 --feature all --remote reader-prod \\
    --remote-project-dir /opt/classic-chat-reader --remote-db-url "jdbc:h2:file:/opt/classic-chat-reader/data/library;DB_CLOSE_DELAY=-1" \\
    --apply-import
EOF
}

fail() {
  echo "Error: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing required command: $1"
}

base64_encode_compact() {
  printf '%s' "$1" | base64 | tr -d '\n'
}

run_local_cache_transfer() {
  local exec_args="$1"
  (
    cd "$ROOT_DIR"
    "$MAVEN_BIN" -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
      -Dexec.mainClass=com.classicchatreader.cli.CacheTransferRunner \
      -Dexec.args="$exec_args"
  )
}

run_remote_cache_transfer() {
  local exec_args="$1"
  local exec_args_b64
  exec_args_b64="$(base64_encode_compact "$exec_args")"
  if [[ "$REMOTE_RUNNER" == "maven" ]]; then
    "${SSH_CMD[@]}" "$REMOTE_TARGET" bash -s -- "$REMOTE_PROJECT_DIR" "$MAVEN_BIN" "$exec_args_b64" <<'EOF'
set -euo pipefail
project_dir="$1"
maven_bin="$2"
exec_args_b64="$3"
if exec_args="$(printf '%s' "$exec_args_b64" | base64 --decode 2>/dev/null)"; then
  :
else
  exec_args="$(printf '%s' "$exec_args_b64" | base64 -d)"
fi
cd "$project_dir"
"$maven_bin" -q -DskipTests org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.classicchatreader.cli.CacheTransferRunner \
  "-Dexec.args=$exec_args"
EOF
  else
    "${SSH_CMD[@]}" "$REMOTE_TARGET" bash -s -- "$REMOTE_PROJECT_DIR" "$REMOTE_JAVA_BIN" "$REMOTE_JAR_PATH" "$exec_args_b64" <<'EOF'
set -euo pipefail
project_dir="$1"
java_bin="$2"
jar_path="$3"
exec_args_b64="$4"
if exec_args="$(printf '%s' "$exec_args_b64" | base64 --decode 2>/dev/null)"; then
  :
else
  exec_args="$(printf '%s' "$exec_args_b64" | base64 -d)"
fi
cd "$project_dir"
# Parse the transfer CLI arg string into argv without eval.
# shellcheck disable=SC2206
args=( $exec_args )
"$java_bin" \
  -Dloader.main=com.classicchatreader.cli.CacheTransferRunner \
  -cp "$jar_path" \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  "${args[@]}"
EOF
  fi
}

run_remote_cmd() {
  local cmd="$1"
  [[ -n "$cmd" ]] || return 0
  # Execute as a direct remote shell command (with tty) so sudo/systemctl behave normally.
  "${SSH_CMD[@]}" -tt "$REMOTE_TARGET" "$cmd"
}

remote_has_command() {
  local command_name="$1"
  "${SSH_CMD[@]}" "$REMOTE_TARGET" bash -s -- "$command_name" <<'EOF'
set -euo pipefail
command_name="$1"
bash -lc "command -v \"$command_name\" >/dev/null 2>&1"
EOF
}

remote_has_file() {
  local file_path="$1"
  "${SSH_CMD[@]}" "$REMOTE_TARGET" bash -s -- "$file_path" <<'EOF'
set -euo pipefail
file_path="$1"
bash -lc "test -f \"$file_path\""
EOF
}

get_remote_home_dir() {
  "${SSH_CMD[@]}" "$REMOTE_TARGET" bash -s <<'EOF'
set -euo pipefail
bash -lc 'printf %s "$HOME"'
EOF
}

resolve_remote_jar_path() {
  local candidate="$REMOTE_JAR_PATH"
  local home_candidate=""
  if [[ -n "$REMOTE_HOME_DIR" ]]; then
    home_candidate="${REMOTE_HOME_DIR}/classic-chat-reader-1.0-SNAPSHOT.jar"
  fi

  if [[ -n "$candidate" ]] && remote_has_file "$candidate"; then
    echo "$candidate"
    return
  fi

  if [[ -n "$home_candidate" ]] && remote_has_file "$home_candidate"; then
    echo "$home_candidate"
    return
  fi

  echo "$candidate"
}

ensure_remote_runner() {
  case "$REMOTE_RUNNER" in
    maven)
      remote_has_command "$MAVEN_BIN" || fail "Remote Maven binary not found: ${MAVEN_BIN}. Install Maven or use --remote-runner jar with --remote-jar-path."
      ;;
    jar)
      remote_has_command "$REMOTE_JAVA_BIN" || fail "Remote Java binary not found: ${REMOTE_JAVA_BIN}."
      REMOTE_JAR_PATH="$(resolve_remote_jar_path)"
      remote_has_file "$REMOTE_JAR_PATH" || fail "Remote jar not found: ${REMOTE_JAR_PATH}."
      ;;
    auto)
      if remote_has_command "$MAVEN_BIN"; then
        REMOTE_RUNNER="maven"
        return
      fi
      REMOTE_JAR_PATH="$(resolve_remote_jar_path)"
      if remote_has_command "$REMOTE_JAVA_BIN" && remote_has_file "$REMOTE_JAR_PATH"; then
        REMOTE_RUNNER="jar"
        return
      fi
      fail "Remote runner auto-detect failed. Maven (${MAVEN_BIN}) missing and jar mode not ready (need ${REMOTE_JAVA_BIN} and ${REMOTE_JAR_PATH})."
      ;;
    *)
      fail "--remote-runner must be one of: auto, maven, jar"
      ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --feature)
      [[ $# -ge 2 ]] || fail "--feature requires a value"
      FEATURE="$2"
      shift 2
      ;;
    --book-source-id)
      [[ $# -ge 2 ]] || fail "--book-source-id requires a value"
      SOURCE_IDS="$2"
      shift 2
      ;;
    --all-cached)
      ALL_CACHED=true
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
    --remote-import-path)
      [[ $# -ge 2 ]] || fail "--remote-import-path requires a value"
      REMOTE_IMPORT_PATH="$2"
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
    --remote-java-bin)
      [[ $# -ge 2 ]] || fail "--remote-java-bin requires a value"
      REMOTE_JAVA_BIN="$2"
      shift 2
      ;;
    --on-conflict)
      [[ $# -ge 2 ]] || fail "--on-conflict requires a value"
      ON_CONFLICT="$2"
      shift 2
      ;;
    --apply-import)
      APPLY_IMPORT=true
      shift
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
    --local-db-url)
      [[ $# -ge 2 ]] || fail "--local-db-url requires a value"
      LOCAL_DB_URL="$2"
      shift 2
      ;;
    --local-db-user)
      [[ $# -ge 2 ]] || fail "--local-db-user requires a value"
      LOCAL_DB_USER="$2"
      shift 2
      ;;
    --local-db-password)
      [[ $# -ge 2 ]] || fail "--local-db-password requires a value"
      LOCAL_DB_PASSWORD="$2"
      shift 2
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

[[ -n "$REMOTE_TARGET" ]] || fail "--remote is required"
[[ -n "$REMOTE_PROJECT_DIR" ]] || fail "--remote-project-dir is required"
[[ -n "$REMOTE_DB_URL" ]] || fail "--remote-db-url is required"
[[ "$ON_CONFLICT" == "skip" || "$ON_CONFLICT" == "overwrite" ]] || fail "--on-conflict must be skip or overwrite"
[[ "$REMOTE_RUNNER" == "auto" || "$REMOTE_RUNNER" == "maven" || "$REMOTE_RUNNER" == "jar" ]] || fail "--remote-runner must be one of: auto, maven, jar"
[[ "$FEATURE" == "recaps" || "$FEATURE" == "quizzes" || "$FEATURE" == "illustrations" || "$FEATURE" == "portraits" || "$FEATURE" == "all" ]] || fail "--feature must be recaps, quizzes, illustrations, portraits, or all"

if [[ "$ALL_CACHED" == "true" && -n "$SOURCE_IDS" ]]; then
  fail "--all-cached and --book-source-id are mutually exclusive"
fi
if [[ "$ALL_CACHED" == "false" && -z "$SOURCE_IDS" ]]; then
  fail "choose one selection mode: --book-source-id or --all-cached"
fi

require_command "$MAVEN_BIN"
require_command ssh
require_command scp

if [[ -n "$SSH_CONFIG_PATH" ]]; then
  SSH_CMD+=(-F "$SSH_CONFIG_PATH")
  SCP_CMD+=(-F "$SSH_CONFIG_PATH")
fi
for ssh_option in "${SSH_OPTIONS[@]-}"; do
  [[ -n "$ssh_option" ]] || continue
  SSH_CMD+=(-o "$ssh_option")
  SCP_CMD+=(-o "$ssh_option")
done

if [[ -z "$REMOTE_JAR_PATH" ]]; then
  REMOTE_JAR_PATH="${REMOTE_PROJECT_DIR}/target/classic-chat-reader-1.0-SNAPSHOT.jar"
fi

REMOTE_HOME_DIR="$(get_remote_home_dir)"
ensure_remote_runner

declare -a FEATURES_TO_TRANSFER=()
if [[ "$FEATURE" == "all" ]]; then
  FEATURES_TO_TRANSFER=(recaps quizzes illustrations portraits)
else
  FEATURES_TO_TRANSFER=("$FEATURE")
fi

if [[ -n "$EXPORT_PATH" && "${#FEATURES_TO_TRANSFER[@]}" -gt 1 ]]; then
  fail "--export-path only supports single-feature transfers; use --export-dir for --feature all"
fi

build_remote_import_path() {
  local current_feature="$1"
  if [[ -z "$REMOTE_IMPORT_PATH" ]]; then
    echo "/tmp/classic-chat-reader-${current_feature}-transfer.json"
    return
  fi
  if [[ "${#FEATURES_TO_TRANSFER[@]}" -eq 1 ]]; then
    echo "$REMOTE_IMPORT_PATH"
    return
  fi
  if [[ "$REMOTE_IMPORT_PATH" == *.* ]]; then
    local ext="${REMOTE_IMPORT_PATH##*.}"
    local stem="${REMOTE_IMPORT_PATH%.*}"
    echo "${stem}-${current_feature}.${ext}"
    return
  fi
  echo "${REMOTE_IMPORT_PATH}-${current_feature}"
}

ts="$(date -u +%Y%m%dT%H%M%SZ)"
export_selector="${SOURCE_IDS//,/__}"
export_selector="${export_selector:-all-cached}"
mkdir -p "$EXPORT_DIR"

declare -a EXPORT_PATHS=()
declare -a REMOTE_PATHS=()

for current_feature in "${FEATURES_TO_TRANSFER[@]}"; do
  current_export_path="$EXPORT_PATH"
  if [[ -z "$current_export_path" ]]; then
    current_export_path="${EXPORT_DIR}/${current_feature}-transfer-${export_selector}-${ts}.json"
  fi
  current_remote_path="$(build_remote_import_path "$current_feature")"

  echo "Step 1/4: Export ${current_feature} locally -> ${current_export_path}"
  export_args="export --feature ${current_feature}"
  if [[ "$ALL_CACHED" == "true" ]]; then
    export_args="${export_args} --all-cached"
  else
    export_args="${export_args} --book-source-id ${SOURCE_IDS}"
  fi
  export_args="${export_args} --apply --output ${current_export_path}"
  if [[ -n "$LOCAL_DB_URL" ]]; then
    export_args="${export_args} --db-url ${LOCAL_DB_URL} --db-user ${LOCAL_DB_USER} --db-password ${LOCAL_DB_PASSWORD}"
  fi
  run_local_cache_transfer "$export_args"
  [[ -f "$current_export_path" ]] || fail "Export file not found: ${current_export_path}"

  echo "Step 2/4: Upload ${current_feature} transfer -> ${REMOTE_TARGET}:${current_remote_path}"
  "${SCP_CMD[@]}" "$current_export_path" "${REMOTE_TARGET}:${current_remote_path}"

  echo "Step 3/4: Remote import dry-run (${current_feature})"
  echo "  Remote runner: ${REMOTE_RUNNER}"
  import_args="import --feature ${current_feature} --input ${current_remote_path} --on-conflict ${ON_CONFLICT} --db-url ${REMOTE_DB_URL} --db-user ${REMOTE_DB_USER} --db-password ${REMOTE_DB_PASSWORD}"
  if [[ -z "$REMOTE_DB_PASSWORD" ]]; then
    import_args="import --feature ${current_feature} --input ${current_remote_path} --on-conflict ${ON_CONFLICT} --db-url ${REMOTE_DB_URL} --db-user ${REMOTE_DB_USER}"
  fi
  run_remote_cache_transfer "$import_args"

  EXPORT_PATHS+=("$current_export_path")
  REMOTE_PATHS+=("$current_remote_path")
done

service_stopped=false
cleanup() {
  if [[ "$service_stopped" == "true" && -n "$REMOTE_START_CMD" ]]; then
    echo "Cleanup: attempting remote service start..."
    run_remote_cmd "$REMOTE_START_CMD" || true
  fi
}
trap cleanup EXIT

if [[ "$APPLY_IMPORT" == "true" ]]; then
  echo "Step 4/4: Remote import apply"
  if [[ -n "$REMOTE_STOP_CMD" ]]; then
    echo "Stopping remote service..."
    run_remote_cmd "$REMOTE_STOP_CMD"
    service_stopped=true
  fi
  for i in "${!FEATURES_TO_TRANSFER[@]}"; do
    current_feature="${FEATURES_TO_TRANSFER[$i]}"
    current_remote_path="${REMOTE_PATHS[$i]}"
    import_args="import --feature ${current_feature} --input ${current_remote_path} --on-conflict ${ON_CONFLICT} --db-url ${REMOTE_DB_URL} --db-user ${REMOTE_DB_USER} --db-password ${REMOTE_DB_PASSWORD}"
    if [[ -z "$REMOTE_DB_PASSWORD" ]]; then
      import_args="import --feature ${current_feature} --input ${current_remote_path} --on-conflict ${ON_CONFLICT} --db-url ${REMOTE_DB_URL} --db-user ${REMOTE_DB_USER}"
    fi
    run_remote_cache_transfer "${import_args} --apply"
  done
  if [[ "$service_stopped" == "true" && -n "$REMOTE_START_CMD" ]]; then
    echo "Starting remote service..."
    run_remote_cmd "$REMOTE_START_CMD"
    service_stopped=false
  fi
else
  echo "Step 4/4: Skipped apply import (use --apply-import to write)"
fi

echo "Done."
