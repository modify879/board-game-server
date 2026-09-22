package com.jsm.boardgame.holdem.application.command.usecase

import com.jsm.boardgame.holdem.domain.model.TableId

interface ExpireConnectionUseCase {
    /**
     * 연결 만료를 처리한다. 미착석이거나 즉시 기립했으면 null 을 돌려준다. 핸드가 진행 중이면
     * 칩을 들고 나갈 수 없어(going south 방지) 기립시키지 않고, "예약이 필요하다" 는 뜻으로
     * 그 테이블 id 를 돌려준다.
     *
     * 예약 저장소 자체는 두지 않는다 - 예약은
     * [com.jsm.boardgame.holdem.infrastructure.timer.ConnectionTimer] 인메모리 맵 하나로 충분한
     * 인프라 concern이라, 그걸 위해 application 에 포트를 하나 더 두면 구현체 하나짜리 인터페이스가
     * 된다. 그 대신 이 유스케이스는 "예약이 필요하다" 는 사실(테이블 id)만 반환값으로 알려주고,
     * ConnectionTimer 가 그 반환값을 보고 자신의 맵에 기록한다.
     */
    fun expire(command: ExpireConnectionCommand): TableId?
}

data class ExpireConnectionCommand(val userId: Long)
