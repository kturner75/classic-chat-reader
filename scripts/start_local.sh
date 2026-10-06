#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

MAVEN_BIN="${MAVEN_BIN:-mvn}"
PROFILE="${SPRING_PROFILES_ACTIVE:-local-dev}"
LOG_FILE="${CCR_LOG_FILE:-logs/ccr-local.log}"

usage() {
  cat <<EOF
Usage:
  scripts/start_local.sh [maven args...]

Starts the app locally with the local-dev Spring profile (PostgreSQL).

Environment:
  MAVEN_BIN                 Maven executable (default: mvn)
  SPRING_PROFILES_ACTIVE    Override profile (default: local-dev)
  CCR_LOG_FILE              Log file (default: logs/ccr-local.log)

Notes:
  - Loads .env.local from the repo root when present (KEY=value lines).
  - App listens on http://localhost:8080 by default.
  - Output still prints to the terminal and is also written to the log file.
    The previous run is kept as <log file>.prev; each start begins a fresh log.
  - Extra args are forwarded to mvn spring-boot:run.

Examples:
  scripts/start_local.sh
  scripts/start_local.sh -Dspring-boot.run.jvmArguments="-Xmx1g"
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

if ! command -v "${MAVEN_BIN}" >/dev/null 2>&1; then
  echo "error: Maven not found (${MAVEN_BIN}). Install Maven or set MAVEN_BIN." >&2
  exit 1
fi

if [[ -f .env.local ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env.local
  set +a
  echo "Loaded .env.local"
else
  echo "No .env.local found (optional). See .env.local.example"
fi

echo "Starting with profile: ${PROFILE}"
echo "Open http://localhost:8080 when ready"
mkdir -p "$(dirname "${LOG_FILE}")"
[[ -f "${LOG_FILE}" ]] && mv "${LOG_FILE}" "${LOG_FILE}.prev"
echo "Logging to ${LOG_FILE}"
# A pipe (not exec) so output reaches both the terminal and the file; pipefail keeps mvn's exit code.
"${MAVEN_BIN}" spring-boot:run -Dspring-boot.run.profiles="${PROFILE}" "$@" 2>&1 | tee "${LOG_FILE}"
