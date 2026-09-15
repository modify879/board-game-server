package com.jsm.boardgame.user.domain.repository

import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.Username

interface UserRepository {
    fun findById(id: UserId): User?
    fun findByUsername(username: Username): User?
    fun existsByUsername(username: Username): Boolean
    fun existsByNickname(nickname: Nickname): Boolean
    fun save(user: User): User
}
