# 홀덤 하우스 룰

상태: 확정(2026-10-11), **미구현** — 구현되면 항목별로 표시를 지운다.

캐시게임 규칙은 대부분 하우스 룰이다(TDA 자체가 캐시게임은 하우스 룰에 맡긴다). 이 문서가 목표 규칙의 유일한 기준이다.
`.claude/rules/holdem.md` 는 구현된 동작을 적으므로, 항목이 구현될 때까지는 코드와 이 문서가 다르다.

## 규칙

| 항목 | 규칙 | 현재 동작 | 근거/출처 |
|---|---|---|---|
| 1. 차례 시간 | 30초, 타임뱅크 없음 | 구현됨 | 타임뱅크는 온라인 사이트 선택 사항 |
| 2. 시간 초과 | 체크 가능하면 자동 체크·자리 유지. 아니면 자동 폴드 후 즉시 좌석을 떠난다(칩은 지갑으로) | 같음(`ExpireTurnService`) | |
| 3. 연결 끊김 | 끊김 자체로는 상태를 바꾸지 않는다(presence 표시만). 핸드 중에는 2번이 처리한다. 핸드가 없는 동안 끊긴 채 3분이 지나면 좌석을 떠난다 — 혼자 남은 끊긴 사람이 테이블을 붙잡아 자동 닫힘을 막지 않게 | 구현됨 | |
| 4. 자리 비움(sit-out) | 두지 않는다. 그래서 놓친 블라인드 규칙도 없다. 자리를 비운 사람이 좌석을 붙잡으면 대기열의 사람이 못 앉는다 | 같음(sit-out 없음) | |
| 5. 바이인 | 20~100BB(4,000~20,000) 사이에서 고른다 | 구현됨 | PokerStars 표준 40~100BB. WSOP Online 이 2024-11 최소를 20BB 로 낮췄다가 숙련자 반발로 30BB 로 올렸다 — 20BB 는 초보 친화 선택임을 알고 고른 것 |
| 6. 충전 | 지갑에 들어온 돈은 지갑에 남는다. 핸드 사이에만 "칩 추가"로 최대 100BB 까지 스택에 넣는다. 핸드 중 불가 | 구현됨 | 영국 도박위원회 RTS 14 — 매번 고객이 고르지 않은 자동 충전 금지(후에 사용자가 켜는 상한 있는 자동 리바이는 허용으로 정정) |
| 7. 재착석(ratholing 방지) | 떠난 뒤 30분 안에 어느 테이블이든(지금은 모든 테이블 블라인드가 같다) 다시 앉으면 떠날 때 가져간 금액 이상으로 앉는다. 100BB 를 넘어도 이 의무가 우선한다. 지갑이 그 금액에 못 미치면 30분이 지날 때까지 앉을 수 없다 | 구현됨 | 카드룸 table stakes 원칙; ACR No Rathole(상한 넘는 재착석 사례); PokerStars Buy-In Obligations(테이블 이동 추적) |
| 8. 서버 재시작 | 3분 기다린 뒤 핸드를 재개한다. 안 돌아온 사람은 2번대로(차례가 오면 체크/폴드·퇴장). 이미 낸 칩은 팟에 남는다 — 판을 무효로 만드는 악용을 막는 대신 장애 손실을 일부 플레이어가 지는 선택. 런아웃 중이면 지금처럼 바로 재개 | 구현됨(`HandRecovery`) | |
| 9. 쇼다운 | 두 명 이상이 쇼다운에 가면 남은 패 전부 공개. 모두 폴드해 한 명만 남으면 공개하지 않는다. 폴드한 패는 공개하지 않는다 | 같음 | 일반 온라인 사이트는 올인이면 전원 공개(TDA Face Up for All-Ins), 아니면 마지막 공격자부터 공개하고 진 쪽은 덮을 수 있다 — 그와 다르게 전부 공개를 고른 것 |
| 10. 보여 주기 | 모두 폴드해 이긴 사람만, 핸드가 끝난 뒤 다음 핸드 시작 전(카운트다운 동안) 두 장을 공개할 수 있다. 한 장 공개·폴드한 사람의 공개는 없다. 공개는 공개 토픽으로 모두에게, 핸드 히스토리에 기록 | 구현됨(히스토리 기록은 11번과 함께) | |
| 11. 핸드 히스토리 | 90일 보관. 관리자는 전체, 플레이어는 자기 핸드만 조회 | 남기지 않음(직전 핸드 결과 1행만) | |
| 12. 앤티·스트래들·레이크 | 없음 | 같음 | |

## 규칙에서 따라 나오는 동작

- 재착석 의무가 지갑 잔액보다 크면 30분이 지날 때까지 앉을 수 없다
- 스택은 플레이 중 100BB 를 넘을 수 있다. 100BB 상한은 바이인과 칩 추가에만 적용한다
- 칩 추가는 스택이 100BB 미만일 때만, 핸드 사이에만 가능하다. 넣을 수 있는 양은 100BB 까지다
- 재착석 의무는 상한보다 우선한다 — 100BB 를 넘는 금액으로 앉을 수 있다
- 자동 합산이 없으므로 입금·출금 취소·관리자 증액은 지갑에만 반영된다. 스택에 넣으려면 칩 추가를 해야 한다
- 3번 때문에 연결 끊김만으로는 핸드 중 좌석이 바뀌지 않는다. 끊긴 사람의 차례는 2번이 처리한다

## 구현 순서(개발 목록)

0. (구현됨) 운영 코드 칩 보존 검사 — 정산·환불 직전 합계를 검사하고 어긋나면 롤백한다. 기능 개발 전 안전장치
1. 차례 시간 30초 + 끊김 처리(3번) — 작음
2. 바이인 범위·칩 추가·자동 합산 폐지·재착석 의무(5·6·7번) — 큼. 클라이언트: 바이인 입력, 칩 추가 UI
3. (구현됨) 재시작 복구 변경(8번)
4. 보여 주기(10번) — 작음. 클라이언트: 버튼
5. 핸드 히스토리(11번) — 큼
6. 시뮬레이션 테스트 — 기능 완성 후. 무작위 시뮬레이션 + 불변식, 최소 규칙 오라클 + 메타모픽

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
