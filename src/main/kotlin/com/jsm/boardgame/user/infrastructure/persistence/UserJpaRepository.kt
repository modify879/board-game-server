package com.jsm.boardgame.user.infrastructure.persistence

import com.jsm.boardgame.user.application.query.UserProfile
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserJpaRepository : JpaRepository<UserJpaEntity, Long> {

    fun existsByUsername(username: String): Boolean

    fun existsByNickname(nickname: String): Boolean

    // 조회 전용 프로젝션. 도메인을 거치지 않고 응답 DTO 를 바로 만든다 (CLAUDE.md 규칙 3).
    @Query(
        "select new com.jsm.boardgame.user.application.query.UserProfile(u.id, u.username, u.nickname, u.profileImageKey) " +
            "from UserJpaEntity u where u.id = :id",
    )
    fun findProfileById(@Param("id") id: Long): UserProfile?
}
