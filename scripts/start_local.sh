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
  CCR_LOG_FILE              Log file (default: logs/ccr-local.log; a relative path is from the repo root)

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
if [[ -f "${LOG_FILE}" ]]; then
  mv "${LOG_FILE}" "${LOG_FILE}.prev"
fi
echo "Logging to ${LOG_FILE}"
# Send output to the terminal and the file via tee, then exec mvn so it stays this process (SIGTERM
# to the script PID still reaches it, and its exit code is the script's). tee -i ignores Ctrl-C so
# Spring's shutdown lines are still written; tee ends on its own when mvn closes the pipe.
exec > >(tee -i "${LOG_FILE}") 2>&1
exec "${MAVEN_BIN}" spring-boot:run -Dspring-boot.run.profiles="${PROFILE}" "$@"
