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
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
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
)

// KotlinJdslJpqlExecutor 를 상속하면 Kotlin JDSL 이 findAll/findPage 등의 실행기를
// 커스텀 구현체로 자동 주입한다 (KotlinJdslJpaRepositoryFactoryBeanPostProcessor).
// findProfileById 의 JDSL 프로젝션은 UserQueryRepositoryAdapter 에서 이 실행기로 수행한다.
interface UserJpaRepository :
    JpaRepository<UserJpaEntity, Long>,
    KotlinJdslJpqlExecutor {

    fun findByUsername(username: String): UserJpaEntity?

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
    )
