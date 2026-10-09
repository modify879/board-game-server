package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.application.port.UserConnections
import org.springframework.messaging.simp.user.SimpUserRegistry
import org.springframework.stereotype.Component

@Component
class StompUserConnections(
    private val userRegistry: SimpUserRegistry,
) : UserConnections {

    override fun isConnected(userId: UserId): Boolean = userRegistry.getUser(userId.value.toString()) != null

    override fun hasOtherSession(userId: UserId, excludingSessionId: String): Boolean =
        userRegistry.getUser(userId.value.toString())?.sessions?.any { it.id != excludingSessionId } == true
}
