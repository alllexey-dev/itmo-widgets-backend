#!/usr/bin/env bash
# verify.sh [build]                  ./gradlew build: compile, every test (Testcontainers PostgreSQL), all checks
# verify.sh test <pattern>...        ./gradlew test --tests <pattern> for each pattern
# verify.sh run -- <gradle args...>  ad hoc Gradle tasks
# verify.sh leaks                    Testcontainers this repository's tests left behind (no slot, no Gradle)
# verify.sh openapi                  regenerate docs/openapi.json (OpenApiSnapshotTest with -Popenapi.record=true)
#
# The one build entry point for agents (L21 BK-01, BK-02b; contract in the verify-script recipe, L04 TC-09).
#
# - Every Gradle part is its own `slot.sh backend -- verify.sh __gradle <args>` call, one after another and never
#   nested, with --max-workers=$ITMO_MAX_WORKERS from the slot; the slot script is
#   ${ITMO_SLOT_SH:-~/proj/.wt/bin/slot.sh}. While that file is not executable the part runs under
#   /usr/bin/lockf -k ~/.cache/itmo-agents/slots/backend.1.lock (the backend slot's own lock), and directly with
#   CI=true, inside a held slot (ITMO_SLOT_HELD) or without /usr/bin/lockf.
# - On macOS, each only when unset: JAVA_HOME = JDK 21, DOCKER_HOST = colima's socket,
#   TESTCONTAINERS_RYUK_DISABLED=true.
# - Gradle gets ITMO_AGENTS_RUN (kept when set); the tests put it on their PostgreSQL container as the label
#   itmo-agents.run, next to the test JVM's pid.
# - run refuses --stop, publish and *ToMavenLocal* tasks, install*/uninstall*, connected*/deviceCheck.
# - The last line of a finished run is `VERIFY B <mode> PASS|FAIL <secs>s <sha7>[+dirty]`.
# - Exit code: 0 pass, 1 fail, 2 refused (usage, missing JDK or docker, forbidden task); refusals print no VERIFY
#   line.

set -u

me=verify.sh
REPO_LETTER=B

refuse() { printf '%s: %s\n' "$me" "$*" >&2; exit 2; }
note() { printf '%s: %s\n' "$me" "$*" >&2; }

script_dir=$(cd "$(dirname "$0")" 2>/dev/null && pwd -P) || refuse "cannot resolve the script directory"
self="$script_dir/$(basename "$0")"
root=$(cd "$script_dir/.." && pwd -P) || refuse "cannot resolve the repository root"
cd "$root" || refuse "cannot enter $root"

# Internal: the command the slot runs, where ITMO_MAX_WORKERS is known. The environment comes from the caller.
if [ "${1:-}" = __gradle ]; then
  shift
  exec ./gradlew ${ITMO_MAX_WORKERS:+"--max-workers=$ITMO_MAX_WORKERS"} "$@"
fi

usage() {
  sed -n '2,6p' "$self" | sed 's/^# //' >&2
  exit 2
}

mode=${1:-build}
[ $# -gt 0 ] && shift
case "$mode" in
  -h | --help) usage ;;
  build | test | run | leaks | openapi) ;;
  *) note "unknown mode '$mode'"; usage ;;
esac

if [ "$(uname -s)" = Darwin ]; then
  if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2> /dev/null) || refuse "no JDK 21 (/usr/libexec/java_home -v 21)"
    export JAVA_HOME
  fi
  if [ -z "${DOCKER_HOST:-}" ] && [ -S "$HOME/.colima/default/docker.sock" ]; then
    export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
  fi
  export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"
fi

# Read by PostgreSqlTestDatabase at runtime, never a task input, so it does not defeat the build cache.
ITMO_AGENTS_RUN=${ITMO_AGENTS_RUN:-$(basename "$root")-$(date +%Y%m%dT%H%M%S)-$$}
export ITMO_AGENTS_RUN

started=$(date +%s)
sha=$(git rev-parse --short=7 HEAD 2> /dev/null || printf 'nogit')
[ -n "$(git status --porcelain 2> /dev/null)" ] && sha="$sha+dirty"

finish() { # rc
  local verdict=PASS
  [ "$1" -eq 0 ] || verdict=FAIL
  printf 'VERIFY %s %s %s %ss %s\n' "$REPO_LETTER" "$mode" "$verdict" "$(($(date +%s) - started))" "$sha"
  [ "$1" -eq 0 ] && exit 0
  exit 1
}

no_args() { [ $# -eq 0 ] || refuse "$mode takes no arguments (got: $*)"; }

slot_sh=${ITMO_SLOT_SH:-$HOME/proj/.wt/bin/slot.sh}
lock_file="$HOME/.cache/itmo-agents/slots/backend.1.lock"

gradle_part() { # gradle-args...
  if [ -x "$slot_sh" ]; then
    note "[backend slot] ./gradlew $*"
    "$slot_sh" backend -- "$self" __gradle "$@"
  elif [ "${CI:-}" = true ] || [ -n "${ITMO_SLOT_HELD:-}" ] || [ ! -x /usr/bin/lockf ]; then
    note "[direct] ./gradlew $*"
    "$self" __gradle "$@"
  else
    mkdir -p "$(dirname "$lock_file")" || refuse "cannot create $(dirname "$lock_file")"
    note "[backend lock] ./gradlew $*"
    /usr/bin/lockf -k "$lock_file" "$self" __gradle "$@"
  fi
}

# ---- modes -----------------------------------------------------------------------------------------------

run_test() {
  local p args
  [ $# -ge 1 ] || refuse "usage: test <pattern>..."
  args=(test)
  for p in "$@"; do
    case "$p" in
      -*) refuse "test takes test name patterns, got '$p'; use run -- for Gradle flags" ;;
    esac
    args+=(--tests "$p")
  done
  gradle_part "${args[@]}"
}

run_run() {
  local arg task
  [ "${1:-}" = -- ] || refuse "usage: run -- <gradle args...>"
  shift
  [ $# -gt 0 ] || refuse "run: no Gradle arguments"
  for arg in "$@"; do
    task=${arg##*:}
    case "$arg" in
      --stop | --stop=*) refuse "run: never --stop (other agents share the daemons)" ;;
      -*) continue ;;
    esac
    case "$task" in
      *ToMavenLocal* | *toMavenLocal*) refuse "run: no publishing to Maven Local ($arg)" ;;
      publish* | *Publish*) refuse "run: no publishing ($arg)" ;;
      install* | uninstall*) refuse "run: no installs ($arg)" ;;
      connected* | deviceCheck) refuse "run: no device tests in Backend ($arg)" ;;
    esac
  done
  gradle_part "$@"
}

run_openapi() {
  # --rerun: a cached record run with the same inputs would skip the write.
  gradle_part test --rerun --tests 'dev.alllexey.itmowidgets.backend.platform.OpenApiSnapshotTest' \
    -Popenapi.record=true
}

# A labelled container is a leftover once the test JVM in its itmo-agents.pid label is gone. A normal JVM exit
# removes its container (SP-22), so only a killed JVM leaves one; nothing here removes containers.
run_leaks() {
  local rows id pid run dir status state leftovers=0
  command -v docker > /dev/null 2>&1 || refuse "leaks: no docker CLI"
  note "containers labelled itmo-agents.run"
  rows=$(docker ps -a --filter label=itmo-agents.run \
    --format '{{.ID}}	{{.Label "itmo-agents.pid"}}	{{.Label "itmo-agents.run"}}	{{.Label "itmo-agents.dir"}}	{{.Status}}') ||
    refuse "leaks: docker ps failed; is colima running?"
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

# ---- main ------------------------------------------------------------------------------------------------

case "$mode" in
  build) no_args "$@"; gradle_part build ;;
  test) run_test "$@" ;;
  run) run_run "$@" ;;
  openapi) no_args "$@"; run_openapi ;;
  leaks) no_args "$@"; run_leaks ;;
esac
finish $?
