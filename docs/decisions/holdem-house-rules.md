# 홀덤 하우스 룰

상태: 확정(2026-10-11).

캐시게임 규칙은 대부분 하우스 룰이다(TDA 자체가 캐시게임은 하우스 룰에 맡긴다). 이 문서가 목표 규칙의 유일한 기준이다.
`.claude/rules/holdem.md` 는 어기면 깨지는 구현 규칙을, `docs/decisions/holdem.md` 는 그 이유를 적는다. 규칙을 바꿀 때는 이 문서를 먼저 고치고 구현한 뒤 두 파일을 맞춘다.

## 규칙

| 항목 | 규칙 | 근거/출처 |
|---|---|---|
| 1. 차례 시간 | 30초, 타임뱅크 없음 | 타임뱅크는 온라인 사이트 선택 사항 |
| 2. 시간 초과 | 체크 가능하면 자동 체크·자리 유지. 아니면 자동 폴드 후 즉시 좌석을 떠난다(칩은 지갑으로) | |
| 3. 연결 끊김 | 끊김 자체로는 상태를 바꾸지 않는다(presence 표시만). 핸드 중에는 2번이 처리한다. 핸드가 없는 동안 끊긴 채 3분이 지나면 좌석을 떠난다 — 혼자 남은 끊긴 사람이 테이블을 붙잡아 자동 닫힘을 막지 않게 | |
| 4. 자리 비움(sit-out) | 두지 않는다. 그래서 놓친 블라인드 규칙도 없다. 자리를 비운 사람이 좌석을 붙잡으면 대기열의 사람이 못 앉는다 | |
| 5. 바이인 | 테이블 정책의 최소~최대 BB 사이에서 고른다(설정 `app.holdem.table-policy.min-buy-in-big-blinds`/`max-buy-in-big-blinds`, 기본 20~100BB = 블라인드 100/200 기준 4,000~20,000). 테이블은 생성 시 정책을 저장한다 | PokerStars 표준 40~100BB. WSOP Online 이 2024-11 최소를 20BB 로 낮췄다가 숙련자 반발로 30BB 로 올렸다 — 20BB 는 초보 친화 선택임을 알고 고른 것 |
| 6. 충전 | 지갑에 들어온 돈은 지갑에 남는다. "칩 추가"로 정책의 최대 바이인 BB(기본 100BB)까지 스택에 넣는다. 핸드 중에는 바로 넣지 않는다 — 참가자는 예약만 걸고(덮어쓰기, 취소 가능) 핸드가 끝난 뒤 별도 트랜잭션에서 min(요청, 최대 스택 − 핸드 끝 스택, 지갑 잔액)을 100 단위로 내림해 적용한다. 예약이 있는 0칩 좌석은 기립하지 않고 적용을 기다리며, 퇴장 예약이 함께 있으면 퇴장이 이긴다 | 영국 도박위원회 RTS 14 — 매번 고객이 고르지 않은 자동 충전 금지(후에 사용자가 켜는 상한 있는 자동 리바이는 허용으로 정정) |
| 7. 재착석(ratholing 방지) | 떠난 뒤 30분 안에 어느 테이블이든(블라인드는 테이블 생성 시 정책으로 고정되며 기본값은 모든 테이블이 같다) 다시 앉으면 떠날 때 가져간 금액 이상으로 앉는다. 최대 바이인(기본 100BB)을 넘어도 이 의무가 우선한다. 지갑이 그 금액에 못 미치면 30분이 지날 때까지 앉을 수 없다 | 카드룸 table stakes 원칙; ACR No Rathole(상한 넘는 재착석 사례); PokerStars Buy-In Obligations(테이블 이동 추적) |
| 8. 서버 재시작 | 3분 기다린 뒤 핸드를 재개한다. 안 돌아온 사람은 2번대로(차례가 오면 체크/폴드·퇴장). 이미 낸 칩은 팟에 남는다 — 판을 무효로 만드는 악용을 막는 대신 장애 손실을 일부 플레이어가 지는 선택. 런아웃 중이면 지금처럼 바로 재개 | |
| 9. 쇼다운 | 두 명 이상이 쇼다운에 가면 남은 패 전부 공개. 모두 폴드해 한 명만 남으면 공개하지 않는다. 폴드한 패는 공개하지 않는다 | 일반 온라인 사이트는 올인이면 전원 공개(TDA Face Up for All-Ins), 아니면 마지막 공격자부터 공개하고 진 쪽은 덮을 수 있다 — 그와 다르게 전부 공개를 고른 것 |
| 10. 보여 주기 | 모두 폴드해 이긴 사람만, 핸드가 끝난 뒤 다음 핸드 시작 전(카운트다운 동안) 두 장을 공개할 수 있다. 한 장 공개·폴드한 사람의 공개는 없다. 공개는 공개 토픽으로 모두에게, 핸드 히스토리에 기록 | |
| 11. 핸드 히스토리 | 90일 보관. 관리자는 전체, 플레이어는 자기 핸드만 조회 | |
| 12. 앤티·스트래들·레이크 | 없음 | |
| 13. 핸드 뒤 퇴장 예약 | 핸드 중 기립을 요청하면 거부하지 않고 예약한다(핸드 참가 좌석만 — 핸드 중이라도 참가하지 않은 좌석은 즉시 기립). 핸드가 끝나면(취소 포함) 일어서고 스택은 지갑으로, 재착석 의무가 걸린다. 취소 가능. DB 에 저장돼 앱을 닫아도·재시작해도 유지 | |
| 14. 올인 런아웃 | 올인으로 더 베팅할 수 없으면 남은 보드를 한 번에 깔지 않고 한 스트리트씩 진행한다(공개 → 플랍/턴/리버 → 쇼다운, `RunoutTimer` 가 `runout-step-delay` 간격) | 사용자 결정 |
| 15. 다음 핸드 카운트다운 | 입장(착석/참가 요청 처리)마다 전체 지연으로 처음부터 다시 센다(3초 남았을 때 들어와도 이어지지 않는다). 기립은 리셋하지 않고, 기립으로 후보가 2명 미만이 되면 취소한다. 수동 시작은 없다 — 대기 중 끼어들면 다른 좌석의 기립 결정 시간을 빼앗는다 | 사용자 결정 |

## 규칙에서 따라 나오는 동작

- 스택은 플레이 중 최대 바이인을 넘을 수 있다. 이 상한은 바이인과 칩 추가에만 적용한다

## 출처

- https://www.pokertda.com/forum/index.php?msg=12743
- https://www.pokertda.com/wp-content/uploads/2011/01/Poker-TDA-Rules-2015-Version-1.0-full-longform-PDF-1.pdf
- https://en.wikipedia.org/wiki/Blind_(poker)
- https://www.pokernews.com/strategy/casino-poker-for-beginners-missed-blinds-and-other-buttons-21220.htm
- https://briggsoft.com/forums/viewtopic.php?p=621
- https://www.techloy.com/ggpoker-vs-pokerstars-comparing-player-value-in-2025/
- https://pokerfuse.com/news/poker-room-news/221959-wsop-increases-cash-game-rake-cap-and-buy-rules/
- https://pokerfuse.com/news/poker-room-news/221668-regulars-unhappy-wsops-decision-reduce-minimum/
- https://pokerfuse.com/news/law-and-regulation/25866-uk-regulator-clarifies-auto-rebuy-not-forbidden/
- https://www.cardplayer.com/?p=1029279
- https://www.pokertube.com/?p=8233
- https://pokerfuse.com/news/poker-room-news/buy-in-obligations-system-to-curb-ratholing-to-roll-out-on-pokerstars-24-02/
- https://betarabia.ladesk.com/996347-21-Disconnection-TO-CHECK
- https://www.pokernews.com/pokerterms/time-bank.htm
