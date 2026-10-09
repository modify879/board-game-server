package com.jsm.boardgame.user.infrastructure.persistence.adapter

import com.jsm.boardgame.user.infrastructure.persistence.entity.UserJpaEntity
import com.jsm.boardgame.user.infrastructure.persistence.entity.UserJpaRepository
import com.jsm.boardgame.user.application.query.view.UserProfile
import com.jsm.boardgame.user.application.query.view.UserSummary
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import com.jsm.boardgame.user.application.query.port.UserQueryRepository
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

    // LIKE 의 와일드카드(% _)와 이스케이프 문자(\)는 사용자 입력이므로 리터럴로 만든다.
    override fun search(query: String?, pageable: Pageable): Page<UserSummary> {
        val pattern = query?.lowercase()?.replace(Regex("""[\\%_]""")) { "\\" + it.value }?.let { "%$it%" }
        return jpa.findPage(pageable) {
            selectNew<UserSummary>(
                path(UserJpaEntity::id),
                path(UserJpaEntity::username),
                path(UserJpaEntity::nickname),
                path(UserJpaEntity::role),
                path(UserJpaEntity::lockedAt),
            ).from(entity(UserJpaEntity::class))
                .whereAnd(
                    pattern?.let {
                        or(
                            lower(path(UserJpaEntity::username)).like(it, '\\'),
                            lower(path(UserJpaEntity::nickname)).like(it, '\\'),
                        )
                    },
                )
                .orderBy(path(UserJpaEntity::id).desc())
        }
    }
}
