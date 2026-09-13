package com.jsm.boardgame.user.infrastructure.persistence

import com.jsm.boardgame.user.application.query.UserProfile
import com.jsm.boardgame.user.application.query.UserQueryRepository
import org.springframework.stereotype.Repository

@Repository
class UserQueryRepositoryAdapter(
    private val jpa: UserJpaRepository,
) : UserQueryRepository {

    // JpaEntity → 도메인 → DTO 로 두 번 매핑하지 않고, JPA 프로젝션 결과를 그대로 위임한다.
    override fun findProfileById(id: Long): UserProfile? = jpa.findProfileById(id)
}
