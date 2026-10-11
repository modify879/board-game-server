package com.jsm.boardgame.holdem.application.command.usecase

import com.jsm.boardgame.holdem.domain.model.TableId

interface ExpireConnectionUseCase {
    /**
     * 미착석이거나 즉시 기립했으면 null 을 돌려준다. 핸드가 진행 중이면 칩을 들고 나갈 수 없어
     * (going south 방지) 기립시키지 않고, "핸드 진행 중이라 미뤘다" 는 뜻으로 그 테이블 id 를 돌려준다.
     * [com.jsm.boardgame.holdem.infrastructure.timer.ConnectionTimer] 가 이를 보고 감시를 다시 건다.
     */
    fun expire(command: ExpireConnectionCommand): TableId?
}

data class ExpireConnectionCommand(val userId: Long)
