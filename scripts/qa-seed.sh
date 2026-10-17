#!/usr/bin/env bash
# qa 프로필 서버(:8081)에 테스트 계정(qauser1~9, qaadmin)을 만들고 지갑을 충전한다. 멱등.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE=${BASE:-http://localhost:8081}
PASSWORD=qapass1234
AMOUNT=10000000

if [[ "$BASE" != *:8081* && "${QA_SEED_FORCE:-}" != 1 ]]; then
  echo "BASE=$BASE 는 qa 서버(:8081)가 아니다. 정말 쏘려면 QA_SEED_FORCE=1" >&2
  exit 1
fi

signup() { # username nickname
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/users" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$PASSWORD\",\"passwordConfirm\":\"$PASSWORD\",\"nickname\":\"$2\"}")
  case "$code" in
    2??) echo "signup $1: created" ;;
    409) echo "signup $1: exists (skip)" ;;
    *) echo "signup $1: FAILED http $code" >&2; exit 1 ;;
  esac
}

for i in 1 2 3 4 5 6 7 8 9; do signup "qauser$i" "QA유저$i"; done
signup qaadmin QA관리자

docker compose -f compose-qa.yaml exec -T postgres psql -U board_game -d board_game -q \
  -c "update users set role='ADMIN' where username='qaadmin'"
echo "qaadmin: role=ADMIN"

TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"qaadmin\",\"password\":\"$PASSWORD\"}" | jq -r .accessToken)
[[ -n "$TOKEN" && "$TOKEN" != null ]] || { echo "qaadmin login FAILED" >&2; exit 1; }
echo "qaadmin: login ok"

for i in 1 2 3 4 5 6 7 8 9; do
  u="qauser$i"
  id=$(curl -sf -G "$BASE/api/admin/users" -H "Authorization: Bearer $TOKEN" --data-urlencode "query=$u" \
    | jq -r --arg u "$u" '.content[] | select(.username==$u) | .id')
  [[ -n "$id" ]] || { echo "$u: id lookup FAILED" >&2; exit 1; }
  resp=$(curl -s -w '\n%{http_code}' -X POST "$BASE/api/admin/wallets/$id/adjustments" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: qa-seed-$u" \
    -d "{\"amount\":$AMOUNT,\"reason\":\"qa seed\"}")
  code=${resp##*$'\n'}
  # 같은 Idempotency-Key 재실행은 409 ADJUSTMENT_ALREADY_APPLIED — 이미 충전된 것이므로 건너뛴다
  [[ "$code" == 2?? || "$resp" == *ADJUSTMENT_ALREADY_APPLIED* ]] || { echo "$u: charge FAILED http $code" >&2; exit 1; }
  echo "$u (id=$id): charged (balance $(curl -sf "$BASE/api/admin/wallets/$id" -H "Authorization: Bearer $TOKEN" | jq -r .balance))"
done
echo "done"
