#!/usr/bin/env bash
# scripts/verify.sh [mode] - the one build and test entry point for agents.
#
#   build (default)            ./gradlew build: compile, every test (Testcontainers PostgreSQL), checks
#   test <pattern>...          ./gradlew test --tests <pattern> for each pattern
#   run -- <gradle args>       ad hoc Gradle tasks
#   leaks                      Testcontainers this repository's tests left behind (no slot, no Gradle)
#   openapi                    regenerate docs/openapi.json (OpenApiSnapshotTest with -Popenapi.record=true)
#
# Gradle runs inside the machine-wide backend slot: ${ITMO_SLOT_SH:-~/proj/.wt/bin/slot.sh} backend --
# when that file is executable, else /usr/bin/lockf -k ~/.cache/itmo-agents/slots/backend.1.lock, so
# only one Backend Testcontainers build runs at a time; a second caller waits. With CI=true, inside a
# held backend slot (ITMO_SLOT_HELD=backend) or without /usr/bin/lockf it runs directly. On macOS it
# sets JDK 21 (when JAVA_HOME is unset), colima's DOCKER_HOST (when unset) and
# TESTCONTAINERS_RYUK_DISABLED=true. Each Gradle call exports ITMO_AGENTS_RUN (kept when set), which the
# tests put on their PostgreSQL container as the label itmo-agents.run, next to the test JVM's pid.
# Last line: `VERIFY B <mode> PASS|FAIL <secs>s <sha7>[+dirty]`. Exit 0 pass, 1 fail, 2 refused.
# Never --stop or publish.

set -u

REPO_LETTER=B
me=verify.sh
start=$(date +%s)
mode=${1:-build}
[ $# -gt 0 ] && shift

cd "$(dirname "$0")/.." || exit 2

sha_label() {
  local sha
  sha=$(git rev-parse --short=7 HEAD 2> /dev/null) || sha=unknown
  [ -n "$(git status --porcelain 2> /dev/null | head -n 1)" ] && sha="$sha+dirty"
  printf '%s' "$sha"
}

finish() { # exit code: 0 pass, 2 refused, anything else fail
  local rc=$1 word=FAIL
  case "$rc" in
    0) word=PASS ;;
    2) ;;
    *) rc=1 ;;
  esac
  printf 'VERIFY %s %s %s %ss %s\n' "$REPO_LETTER" "$mode" "$word" "$(($(date +%s) - start))" "$(sha_label)"
  exit "$rc"
}

refuse() {
  printf '%s: refused: %s\n' "$me" "$*" >&2
  finish 2
}

step() { printf '\n== verify.sh %s: %s\n' "$mode" "$*"; }

if [ "$(uname -s)" = Darwin ]; then
  if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2> /dev/null) || refuse "no JDK 21 (/usr/libexec/java_home -v 21)"
    export JAVA_HOME
  fi
  if [ -z "${DOCKER_HOST:-}" ] && [ -S "$HOME/.colima/default/docker.sock" ]; then
    export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
  fi
  export TESTCONTAINERS_RYUK_DISABLED=true
fi

# Read by PostgreSqlTestDatabase at runtime, never a task input, so it does not defeat the build cache.
ITMO_AGENTS_RUN=${ITMO_AGENTS_RUN:-$(basename "$PWD")-$(date +%Y%m%dT%H%M%S)-$$}
export ITMO_AGENTS_RUN

slot_sh=${ITMO_SLOT_SH:-$HOME/proj/.wt/bin/slot.sh}
lock_file="$HOME/.cache/itmo-agents/slots/backend.1.lock"

gradle() { # gradle-args...
  local -a wrap
  if [ "${CI:-}" = true ] || [ "${ITMO_SLOT_HELD:-}" = backend ]; then
    wrap=()
  elif [ -x "$slot_sh" ]; then
    wrap=("$slot_sh" backend --)
  elif [ -x /usr/bin/lockf ]; then
    mkdir -p "$(dirname "$lock_file")" || refuse "cannot create $(dirname "$lock_file")"
    wrap=(/usr/bin/lockf -k "$lock_file")
  else
    wrap=()
  fi
  step "${wrap[*]:-direct}: ./gradlew $*"
  # ITMO_MAX_WORKERS is set by slot.sh inside the slot, so it is expanded there.
  ${wrap[@]+"${wrap[@]}"} /bin/bash -c 'exec ./gradlew ${ITMO_MAX_WORKERS:+--max-workers=$ITMO_MAX_WORKERS} "$@"' gradlew "$@"
}

# A labelled container is a leftover once the test JVM in its itmo-agents.pid label is gone. A normal JVM
# exit removes its container (SP-22), so only a killed JVM leaves one; nothing here removes containers.
leaks() {
  local rows id pid run dir status state leftovers=0
  command -v docker > /dev/null 2>&1 || refuse "no docker CLI"
  step "containers labelled itmo-agents.run"
  rows=$(docker ps -a --filter label=itmo-agents.run \
    --format '{{.ID}}	{{.Label "itmo-agents.pid"}}	{{.Label "itmo-agents.run"}}	{{.Label "itmo-agents.dir"}}	{{.Status}}') ||
    refuse "docker ps failed; is colima running?"
  while IFS=$'\t' read -r id pid run dir status; do
    [ -n "$id" ] || continue
    state=leftover
    case "$(ps -p "${pid:-0}" -o comm= 2> /dev/null)" in
      *java*) state=running ;;
    esac
    printf '%-8s %s pid %s run %s dir %s (%s)\n' "$state" "$id" "${pid:-?}" "${run:-?}" "${dir:-?}" "$status"
    [ "$state" = running ] || leftovers=$((leftovers + 1))
  done <<< "$rows"
  printf 'leftovers: %s\n' "$leftovers"
  if [ "$leftovers" -gt 0 ]; then
    printf 'remove them with: docker rm -f <id>...\n'
    return 1
  fi
}

check_args() { # refuse tasks and flags agents must never run
  local a
  for a in "$@"; do
    case "$a" in
      --stop | *publish* | *Publish*) refuse "$a is forbidden for agents" ;;
    esac
  done
}

case "$mode" in
  build)
    [ $# -eq 0 ] || refuse "build takes no arguments"
    gradle build || finish 1
    ;;
  test)
    [ $# -ge 1 ] || refuse "usage: verify.sh test <pattern>..."
    args=(test)
    for p in "$@"; do
      case "$p" in
        -*) refuse "test takes test name patterns, got '$p'; use run -- for Gradle flags" ;;
      esac
      args+=(--tests "$p")
    done
    gradle "${args[@]}" || finish 1
    ;;
  run)
    [ "${1:-}" = "--" ] && shift
    [ $# -ge 1 ] || refuse "usage: verify.sh run -- <gradle args>"
    check_args "$@"
    gradle "$@" || finish 1
    ;;
  openapi)
    [ $# -eq 0 ] || refuse "openapi takes no arguments"
    # --rerun: a cached record run with the same inputs would skip the write.
    gradle test --rerun --tests 'dev.alllexey.itmowidgets.backend.platform.OpenApiSnapshotTest' -Popenapi.record=true || finish 1
    ;;
  leaks)
    [ $# -eq 0 ] || refuse "leaks takes no arguments"
    leaks || finish 1
    ;;
  -h | --help | help)
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    refuse "unknown mode '$mode' (build, test, run, leaks, openapi)"
    ;;
esac
finish 0
