package com.jsm.boardgame.user.infrastructure.acl

import com.jsm.boardgame.holdem.application.query.service.UserTablePresenceQueryService
import com.jsm.boardgame.user.application.port.TablePresence
import com.jsm.boardgame.user.domain.model.UserId
import org.springframework.stereotype.Component

@Component
class TablePresenceAdapter(
    private val holdem: UserTablePresenceQueryService, // holdem 의 공개 application 계약만 부른다
) : TablePresence {
    override fun isAtAnyTable(userId: UserId): Boolean = holdem.isAtAnyTable(userId.value)
}
