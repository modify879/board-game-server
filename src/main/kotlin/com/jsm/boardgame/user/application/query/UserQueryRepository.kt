package com.jsm.boardgame.user.application.query

interface UserQueryRepository {
    fun findProfileById(id: Long): UserProfile?
}
