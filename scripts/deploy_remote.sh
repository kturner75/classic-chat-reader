#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

MAVEN_BIN="${MAVEN_BIN:-mvn}"
SSH_TARGET="${SSH_TARGET:-pdr}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/kevin}"
JAR_PATH="${JAR_PATH:-${ROOT_DIR}/target/classic-chat-reader-1.0-SNAPSHOT.jar}"
REMOTE_JAR_PATH="${REMOTE_JAR_PATH:-~/classic-chat-reader-1.0-SNAPSHOT.jar}"
REMOTE_DEPLOY_CMD="${REMOTE_DEPLOY_CMD:-/root/deploy_noninteractive.sh}"

SKIP_BUILD=false
SKIP_UPLOAD=false
SKIP_REMOTE_DEPLOY=false

usage() {
  cat <<EOF
Usage:
  scripts/deploy_remote.sh [options]

Default flow:
  1) Build JAR with: mvn clean package
  2) Upload JAR with: scp -i ~/.ssh/kevin target/classic-chat-reader-1.0-SNAPSHOT.jar pdr:~/
  3) Run remote deploy script: ssh pdr /root/deploy.sh

Options:
  --ssh-target <host-or-alias>     SSH target (default: ${SSH_TARGET})
  --ssh-key <path>                 SSH private key path (default: ${SSH_KEY})
  --maven-bin <bin>                Maven executable (default: ${MAVEN_BIN})
  --jar-path <path>                Local JAR path (default: ${JAR_PATH})
  --remote-jar-path <path>         Remote upload path (default: ${REMOTE_JAR_PATH})
  --remote-deploy-cmd "<cmd>"      Remote deploy command (default: ${REMOTE_DEPLOY_CMD})
  --skip-build                     Skip mvn clean package
  --skip-upload                    Skip scp upload
  --skip-remote-deploy             Skip remote deploy command
  --help                           Show help

Examples:
  scripts/deploy_remote.sh

  scripts/deploy_remote.sh --ssh-target pdr --ssh-key ~/.ssh/kevin

  scripts/deploy_remote.sh --skip-build
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
    --maven-bin)
      [[ $# -ge 2 ]] || fail "--maven-bin requires a value"
      MAVEN_BIN="$2"
      shift 2
      ;;
    --jar-path)
      [[ $# -ge 2 ]] || fail "--jar-path requires a value"
      JAR_PATH="$2"
      shift 2
      ;;
    --remote-jar-path)
      [[ $# -ge 2 ]] || fail "--remote-jar-path requires a value"
      REMOTE_JAR_PATH="$2"
      shift 2
      ;;
    --remote-deploy-cmd)
      [[ $# -ge 2 ]] || fail "--remote-deploy-cmd requires a value"
      REMOTE_DEPLOY_CMD="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --skip-upload)
      SKIP_UPLOAD=true
      shift
      ;;
    --skip-remote-deploy)
      SKIP_REMOTE_DEPLOY=true
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

require_command ssh
require_command scp
if [[ "${SKIP_BUILD}" == "false" ]]; then
  require_command "${MAVEN_BIN}"
fi

if [[ -n "${SSH_KEY}" && ! -f "${SSH_KEY}" ]]; then
  fail "SSH key not found: ${SSH_KEY}"
fi

echo "Deploy settings:"
echo "  target      : ${SSH_TARGET}"
echo "  local jar   : ${JAR_PATH}"
echo "  remote jar  : ${REMOTE_JAR_PATH}"
echo "  remote cmd  : ${REMOTE_DEPLOY_CMD}"

if [[ "${SKIP_BUILD}" == "false" ]]; then
  echo ""
  echo "[1/3] Building application JAR..."
  (
    cd "${ROOT_DIR}"
    "${MAVEN_BIN}" clean package
  )
else
  echo ""
  echo "[1/3] Skipped build (--skip-build)"
fi

if [[ "${SKIP_UPLOAD}" == "false" ]]; then
  [[ -f "${JAR_PATH}" ]] || fail "JAR not found: ${JAR_PATH}"
  echo ""
  echo "[2/3] Uploading JAR to remote host..."
  if [[ -n "${SSH_KEY}" ]]; then
    scp -i "${SSH_KEY}" "${JAR_PATH}" "${SSH_TARGET}:${REMOTE_JAR_PATH}"
  else
    scp "${JAR_PATH}" "${SSH_TARGET}:${REMOTE_JAR_PATH}"
  fi
else
  echo ""
  echo "[2/3] Skipped upload (--skip-upload)"
fi

if [[ "${SKIP_REMOTE_DEPLOY}" == "false" ]]; then
  echo ""
  echo "[3/3] Running remote deploy command..."
  if [[ -n "${SSH_KEY}" ]]; then
    ssh -i "${SSH_KEY}" "${SSH_TARGET}" "${REMOTE_DEPLOY_CMD}"
  else
    ssh "${SSH_TARGET}" "${REMOTE_DEPLOY_CMD}"
  fi
else
  echo ""
  echo "[3/3] Skipped remote deploy (--skip-remote-deploy)"
fi

echo ""
echo "Deploy workflow complete."
