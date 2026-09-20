package com.jsm.boardgame.user.application.query.port

import com.jsm.boardgame.user.application.query.view.UserProfile

interface UserQueryRepository {
    fun findProfileById(id: Long): UserProfile?
    fun existsById(id: Long): Boolean
}
