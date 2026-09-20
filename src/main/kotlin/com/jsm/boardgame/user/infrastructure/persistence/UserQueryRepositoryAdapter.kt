package com.jsm.boardgame.user.infrastructure.persistence

import com.jsm.boardgame.user.application.query.UserProfile
import com.jsm.boardgame.user.application.query.UserQueryRepository
import org.springframework.stereotype.Repository

@Repository
class UserQueryRepositoryAdapter(
    private val jpa: UserJpaRepository,
) : UserQueryRepository {

    // path/entity/selectNew/eq 는 findAll 에 전달되는 람다의 리시버(Jpql)가 제공하는
    // 멤버(확장) 함수라 별도 import 없이 쓸 수 있다.
    override fun findProfileById(id: Long): UserProfile? =
        jpa.findAll {
            selectNew<UserProfile>(
                path(UserJpaEntity::id),
                path(UserJpaEntity::username),
                path(UserJpaEntity::nickname),
                path(UserJpaEntity::profileImageKey),
            ).from(entity(UserJpaEntity::class))
                .where(path(UserJpaEntity::id).eq(id))
        }.firstOrNull()

    override fun existsById(id: Long): Boolean =
        jpa.findAll {
            select(path(UserJpaEntity::id))
                .from(entity(UserJpaEntity::class))
                .where(path(UserJpaEntity::id).eq(id))
        }.firstOrNull() != null
}
