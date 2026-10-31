package com.jsm.boardgame.holdem.application.query.service

import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.domain.model.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 다른 컨텍스트(user 의 탈퇴 사전 조건)가 "이 사용자가 홀덤에 묶여 있는가" 만 물을 때 쓰는 공개 계약이다.
 * 좌석에 앉아 있거나(DB) 참가 대기열에 있으면(메모리) 묶여 있다.
 */
@Service
@Transactional(readOnly = true)
class UserTablePresenceQueryService(
    private val holdemTableQuery: HoldemTableQueryRepository,
    private val joinQueue: JoinQueue,
) {
    fun isAtAnyTable(userId: Long): Boolean =
        holdemTableQuery.findSeatOf(userId) != null || joinQueue.isQueued(UserId(userId))
}
