package com.jsm.boardgame.user.domain.model

import java.time.Instant

class User private constructor(
    val id: UserId?,
    val username: Username,
    passwordHash: PasswordHash,
    nickname: Nickname,
    profileImageKey: ProfileImageKey?,
    role: UserRole,
    lockedAt: Instant?,
) {
    var passwordHash: PasswordHash = passwordHash
        private set
    var nickname: Nickname = nickname
        private set
    var profileImageKey: ProfileImageKey? = profileImageKey
        private set
    var role: UserRole = role
        private set
    var lockedAt: Instant? = lockedAt
        private set

    val isLocked: Boolean get() = lockedAt != null

    fun changeRole(role: UserRole) {
        this.role = role
    }

    /** 이미 잠겨 있으면 그대로 둔다 — 최초 잠긴 시각을 보존한다. */
    fun lock(at: Instant) {
        if (lockedAt == null) lockedAt = at
    }

    fun unlock() {
        lockedAt = null
    }

    companion object {
        /** 신규 가입. 프로필 이미지는 항상 null 로 시작한다(= 기본 프로필). 역할은 항상 USER 로 시작한다. 잠기지 않은 채로 시작한다. */
        fun register(username: Username, passwordHash: PasswordHash, nickname: Nickname): User =
            User(
                id = null,
                username = username,
                passwordHash = passwordHash,
                nickname = nickname,
                profileImageKey = null,
                role = UserRole.USER,
                lockedAt = null,
            )

        /** 영속 복원 전용 — 검증하지 않는다. `lockedAt` 기본값 null 은 이 필드가 없던 시절의 호출부(테스트 픽스처)가 깨지지 않게 하기 위함이다. */
        fun reconstitute(
            id: UserId,
            username: Username,
            passwordHash: PasswordHash,
            nickname: Nickname,
            profileImageKey: ProfileImageKey?,
            role: UserRole,
            lockedAt: Instant? = null,
        ): User = User(
            id = id,
            username = username,
            passwordHash = passwordHash,
            nickname = nickname,
            profileImageKey = profileImageKey,
            role = role,
            lockedAt = lockedAt,
        )
    }
}
