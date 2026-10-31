package com.jsm.boardgame.user.domain.repository

import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.Username

interface UserRepository {
    fun findByUsername(username: Username): User?
    fun findById(id: UserId): User?

    /**
     * 사용자를 바꾸는 명령은 모두 이것으로 읽고 같은 트랜잭션에서 [save] 한다 — 행을 커밋까지 잠가 동시 변경이
     * 서로를 덮어쓰지 않게 한다. 호출자의 트랜잭션 안에서만 부른다. 지갑 락을 쥘 거라면 지갑을 먼저 잠근다.
     */
    fun findByIdForUpdate(id: UserId): User?
    fun existsByUsername(username: Username): Boolean
    fun existsByNickname(nickname: Nickname): Boolean
    fun save(user: User): User
}
