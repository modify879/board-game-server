#!/usr/bin/env bash
# 서버 기동/종료 스크립트. 사용법: scripts/server.sh <main|qa> <start|stop|restart|status|logs> [ref]
#
# 서버는 반드시 전용 실행 워크트리(main=<저장소 부모>/board-game-run, qa=<저장소 부모>/board-game-qa)에서 띄운다.
# 사람이 개발하는 메인 저장소 트리에서는 절대 bootRun 하지 않는다 — bootRun 은 그 트리의 build/classes 를
# 직접 읽기 때문에, 같은 트리에서 다른 빌드를 하면 실행 중 서버가 새로 컴파일된 클래스를 섞어 읽어 깨진다.
# 실행 워크트리는 detached HEAD 로 특정 커밋(ref)만 가리킨다.
set -euo pipefail

usage() { echo "사용법: $0 <main|qa> <start|stop|restart|status|logs> [ref]" >&2; exit 2; }
[[ $# -ge 2 && $# -le 3 ]] || usage
TARGET=$1 CMD=$2 REF=${3:-develop}

# 메인 저장소 경로: 워크트리에서 실행해도 git common dir 의 부모는 같다.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
COMMON=$(git -C "$SCRIPT_DIR" rev-parse --path-format=absolute --git-common-dir)
REPO=$(dirname "$COMMON")
PARENT=$(dirname "$REPO")

case $TARGET in
  main) PORT=8080 WT=$PARENT/board-game-run TASK=bootRun ;;
  qa)   PORT=8081 WT=$PARENT/board-game-qa  TASK=qaBootRun ;;
  *)    usage ;;
esac
LOG=$WT/logs/server.log

port_pid() { ss -ltnp "sport = :$PORT" 2>/dev/null | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true; }

# 그 워크트리가 cwd 인 gradle 래퍼 java 프로세스 (Gradle 데몬은 공유되므로 건드리지 않는다)
wrapper_pids() {
  local p
  for p in /proc/[0-9]*; do
    [[ $(readlink "$p/cwd" 2>/dev/null) == "$WT" ]] || continue
    [[ $(basename "$(readlink "$p/exe" 2>/dev/null)") == java ]] || continue
    tr '\0' ' ' < "$p/cmdline" 2>/dev/null | grep -q GradleWrapperMain && echo "${p#/proc/}"
  done
}

do_start() {
  if [[ -n $(port_pid) ]]; then echo "[$TARGET] 이미 실행 중 (포트 $PORT, PID $(port_pid))"; return 0; fi
  if [[ -z ${JAVA_HOME:-} ]] && ! command -v java >/dev/null; then
    echo "[$TARGET] 오류: JAVA_HOME 도 없고 PATH 에 java 도 없다" >&2; return 1
  fi
  if [[ -d $WT ]]; then
    echo "[$TARGET] 워크트리 $WT 를 $REF 로 전환"
    git -C "$WT" switch --detach "$REF"
  else
    echo "[$TARGET] 워크트리 $WT 를 $REF 로 생성"
    git -C "$REPO" worktree add --detach "$WT" "$REF"
  fi
  mkdir -p "$WT/logs"
  [[ -f $LOG ]] && mv -f "$LOG" "$LOG.1"
  echo "[$TARGET] $TASK 기동 중 (최대 300초 대기)"
  (cd "$WT" && setsid nohup ./gradlew "$TASK" > "$LOG" 2>&1 < /dev/null &)
  local i
  for ((i = 0; i < 300; i++)); do
    if grep -qE 'Started BoardGameApplication' "$LOG" 2>/dev/null; then
      echo "[$TARGET] 기동 완료: 포트 $PORT, 커밋 $(git -C "$WT" rev-parse --short HEAD), 로그 $LOG"
      return 0
    fi
    if grep -qE 'APPLICATION FAILED|BUILD FAILED' "$LOG" 2>/dev/null; then break; fi
    sleep 1
  done
  echo "[$TARGET] 기동 실패 또는 시간 초과. 로그 마지막 40줄:" >&2
  tail -n 40 "$LOG" >&2
  return 1
}

do_stop() {
  local pid wp
  pid=$(port_pid); wp=$(wrapper_pids)
  if [[ -z $pid && -z $wp ]]; then echo "[$TARGET] 실행 중 아님"; return 0; fi
  [[ -n $pid ]] && { echo "[$TARGET] 앱 PID $pid 에 SIGTERM"; kill -TERM "$pid" 2>/dev/null || true; }
  [[ -n $wp ]] && { echo "[$TARGET] gradle 래퍼 PID $(echo $wp) 에 SIGTERM"; kill -TERM $wp 2>/dev/null || true; }
  local i
  for ((i = 0; i < 30; i++)); do
    [[ -z $(port_pid) ]] && { echo "[$TARGET] 종료 완료"; return 0; }
    sleep 1
  done
  echo "[$TARGET] 경고: 30초 내 종료되지 않아 SIGKILL" >&2
  pid=$(port_pid); [[ -n $pid ]] && kill -KILL "$pid" 2>/dev/null || true
  wp=$(wrapper_pids); [[ -n $wp ]] && kill -KILL $wp 2>/dev/null || true
}

do_status() {
  local pid; pid=$(port_pid)
  if [[ -n $pid ]]; then echo "[$TARGET] 실행 중: 포트 $PORT, PID $pid"; else echo "[$TARGET] 실행 중 아님: 포트 $PORT"; fi
  if [[ -d $WT ]]; then echo "[$TARGET] 워크트리 $WT @ $(git -C "$WT" log --oneline -1)"; else echo "[$TARGET] 워크트리 없음: $WT"; fi
  echo "[$TARGET] 로그 $LOG"
}

case $CMD in
  start)   do_start ;;
  stop)    do_stop ;;
  restart) do_stop; do_start ;;
  status)  do_status ;;
  logs)    exec tail -n 100 -f "$LOG" ;;
  *)       usage ;;
esac
