package com.jsm.boardgame.user.infrastructure.persistence.entity

import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.ProfileImageKey
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.model.Username
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Entity
@Table(
    name = "users",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_users_username", columnNames = ["username"]),
        UniqueConstraint(name = "uk_users_nickname", columnNames = ["nickname"]),
    ],
)
class UserJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "username", columnDefinition = "text", nullable = false)
    var username: String,

    @Column(name = "password_hash", columnDefinition = "text", nullable = false)
    var passwordHash: String,

    @Column(name = "nickname", columnDefinition = "text", nullable = false)
    var nickname: String,

    @Column(name = "profile_image_key", columnDefinition = "text")
    var profileImageKey: String?,

    // ddl-auto: update 로 기존 행이 있는 개발 DB 에 NOT NULL 컬럼을 추가할 때 실패하지 않도록 default 를 둔다.
    @Column(name = "role", columnDefinition = "text default 'USER'", nullable = false)
    var role: String,

    @Column(name = "locked_at", columnDefinition = "timestamptz")
    var lockedAt: Instant?,

    @Column(name = "withdrawn_at", columnDefinition = "timestamptz")
    var withdrawnAt: Instant?,
)

// KotlinJdslJpqlExecutor 를 상속하면 Kotlin JDSL 이 findAll/findPage 등의 실행기를
// 커스텀 구현체로 자동 주입한다 (KotlinJdslJpaRepositoryFactoryBeanPostProcessor).
// findProfileById 의 JDSL 프로젝션은 UserQueryRepositoryAdapter 에서 이 실행기로 수행한다.
interface UserJpaRepository :
    JpaRepository<UserJpaEntity, Long>,
    KotlinJdslJpqlExecutor {

    // 탈퇴한 사용자는 명령 경로에서 보이지 않는다 — 가시성은 어댑터 한 곳에서 정한다.
    fun findByIdAndWithdrawnAtIsNull(id: Long): UserJpaEntity?

    fun findByUsernameAndWithdrawnAtIsNull(username: String): UserJpaEntity?

    /**
     * 명령 경로의 "읽고 고쳐 저장" 전용. 행을 트랜잭션 끝까지 잠근다 — Hibernate 의 PostgreSQL 방언은
     * `PESSIMISTIC_WRITE` 를 `for no key update` 로 내보내 지갑·좌석의 FK 검사(`for key share`)를 막지 않는다
     * (`UserRepositoryAdapterIntegrationTest`). 잠금 순서는 지갑 행 → 사용자 행이다(`.claude/rules/transactions.md`).
     * 락을 기다린 뒤 Postgres 가 WHERE 를 새 행 버전으로 다시 평가하므로 그사이 탈퇴했으면 null 이다.
     * `MANDATORY` 인 이유는 `WalletJpaRepository.findByUserIdForUpdate` 와 같다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    fun findForUpdateByIdAndWithdrawnAtIsNull(id: Long): UserJpaEntity?

    fun existsByUsername(username: String): Boolean

    fun existsByNickname(nickname: String): Boolean
}

fun UserJpaEntity.toDomain(): User =
    User.reconstitute(
        id = UserId(id),
        username = Username.reconstitute(username),
        passwordHash = PasswordHash(passwordHash),
        nickname = Nickname.reconstitute(nickname),
        profileImageKey = profileImageKey?.let(ProfileImageKey::reconstitute),
        role = UserRole.valueOf(role),
        lockedAt = lockedAt,
        withdrawnAt = withdrawnAt,
    )

fun User.toJpaEntity(): UserJpaEntity =
    UserJpaEntity(
        id = id?.value ?: 0,
        username = username.value,
        passwordHash = passwordHash.value,
        nickname = nickname.value,
        profileImageKey = profileImageKey?.value,
        role = role.name,
        lockedAt = lockedAt,
        withdrawnAt = withdrawnAt,
    )
