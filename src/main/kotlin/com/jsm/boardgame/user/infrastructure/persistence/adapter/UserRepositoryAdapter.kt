package com.jsm.boardgame.user.infrastructure.persistence.adapter

import com.jsm.boardgame.user.infrastructure.persistence.entity.UserJpaRepository
import com.jsm.boardgame.user.infrastructure.persistence.entity.toDomain
import com.jsm.boardgame.user.infrastructure.persistence.entity.toJpaEntity
import com.jsm.boardgame.common.persistence.violatedConstraint
import com.jsm.boardgame.user.domain.exception.DuplicateNicknameException
import com.jsm.boardgame.user.domain.exception.DuplicateUsernameException
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository

private const val CONSTRAINT_USERNAME = "uk_users_username"
private const val CONSTRAINT_NICKNAME = "uk_users_nickname"

@Repository
class UserRepositoryAdapter(
    private val jpa: UserJpaRepository,
) : UserRepository {

    override fun findByUsername(username: Username): User? =
        jpa.findByUsername(username.value)?.toDomain()

    override fun findById(id: UserId): User? =
        jpa.findById(id.value).orElse(null)?.toDomain()

    override fun existsByUsername(username: Username): Boolean =
        jpa.existsByUsername(username.value)

    override fun existsByNickname(nickname: Nickname): Boolean =
        jpa.existsByNickname(nickname.value)

    /**
     * unique 제약 위반을 도메인 예외로 변환한다. 어떤 제약이 깨졌는지 모르면
     * (알 수 없는 제약, 혹은 다른 종류의 무결성 위반) 원래 예외를 그대로 던져 숨기지 않는다.
     */
    override fun save(user: User): User =
        try {
            jpa.saveAndFlush(user.toJpaEntity()).toDomain()
        } catch (e: DataIntegrityViolationException) {
            throw translate(e)
        }

    private fun translate(e: DataIntegrityViolationException): RuntimeException {
        val constraintName = e.violatedConstraint(CONSTRAINT_USERNAME, CONSTRAINT_NICKNAME) ?: return e
        return when (constraintName) {
            CONSTRAINT_USERNAME -> DuplicateUsernameException("unique 제약 위반: $constraintName")
            CONSTRAINT_NICKNAME -> DuplicateNicknameException("unique 제약 위반: $constraintName")
            else -> e
        }
    }
}
