package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.port.UserConnections
import org.springframework.messaging.simp.user.SimpUserRegistry
import org.springframework.stereotype.Component

@Component
class StompUserConnections(
    private val userRegistry: SimpUserRegistry,
) : UserConnections {

    override fun isConnected(userId: Long): Boolean = userRegistry.getUser(userId.toString()) != null

    override fun hasOtherSession(userId: Long, excludingSessionId: String): Boolean =
        userRegistry.getUser(userId.toString())?.sessions?.any { it.id != excludingSessionId } == true
}
