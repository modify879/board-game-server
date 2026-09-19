package com.jsm.boardgame.user.infrastructure.persistence

import com.jsm.boardgame.user.domain.exception.DuplicateNicknameException
import com.jsm.boardgame.user.domain.exception.DuplicateUsernameException
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.hibernate.exception.ConstraintViolationException
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
            jpa.save(user.toJpaEntity()).toDomain()
        } catch (e: DataIntegrityViolationException) {
            throw translate(e)
        }

    private fun translate(e: DataIntegrityViolationException): RuntimeException {
        val constraintName = extractConstraintName(e) ?: return e
        return when {
            constraintName.equals(CONSTRAINT_USERNAME, ignoreCase = true) ->
                DuplicateUsernameException("unique 제약 위반: $constraintName")
            constraintName.equals(CONSTRAINT_NICKNAME, ignoreCase = true) ->
                DuplicateNicknameException("unique 제약 위반: $constraintName")
            else -> e
        }
    }

    /**
     * 예외 체인에서 Hibernate 의 ConstraintViolationException 을 먼저 찾아
     * `.constraintName` 을 쓴다. 드라이버/커넥션 풀에 따라 래핑되지 않거나
     * 제약 이름을 못 채워주는 경우가 있어, 못 찾으면 예외 메시지 전체 문자열에서
     * 제약 이름을 찾는다. PostgreSQL 은 제약 이름을 소문자로 저장하므로
     * 비교는 대소문자 무시로 한다.
     */
    private fun extractConstraintName(e: DataIntegrityViolationException): String? {
        val fromChain = generateSequence<Throwable>(e) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .firstOrNull()
            ?.constraintName

        if (fromChain != null) {
            return fromChain
        }

        val message = e.message.orEmpty()
        return listOf(CONSTRAINT_USERNAME, CONSTRAINT_NICKNAME)
            .firstOrNull { message.contains(it, ignoreCase = true) }
    }
}
