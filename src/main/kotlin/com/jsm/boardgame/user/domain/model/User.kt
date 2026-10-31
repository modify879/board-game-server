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
    withdrawnAt: Instant?,
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
    var withdrawnAt: Instant? = withdrawnAt
        private set

    val isLocked: Boolean get() = lockedAt != null

    /**
     * 탈퇴 처리(소프트 삭제). 닉네임을 `withdrawn-user-{id}` 묘비로 바꿔 원래 닉네임을 반납한다 — `uk_users_nickname` 은 그대로 두고
     * 다른 사람이 그 닉네임을 쓸 수 있게 하는 방법이다. 묘비는 15자 이상이라 유효한 닉네임(12자 이하)과 절대 겹치지 않는다.
     * 사용자명은 반납하지 않는다. 지워야 할 파일이 있도록 이전 프로필 이미지 키를 돌려준다.
     */
    fun withdraw(at: Instant): ProfileImageKey? {
        val id = checkNotNull(id) { "저장되지 않은 사용자는 탈퇴할 수 없다" }
        val previousImageKey = profileImageKey
        withdrawnAt = at
        nickname = Nickname.reconstitute("withdrawn-user-${id.value}")
        profileImageKey = null
        return previousImageKey
    }

    fun changeRole(role: UserRole) {
        this.role = role
    }

    /** `null` 이면 기본 이미지로 돌아간다. */
    fun changeProfileImage(key: ProfileImageKey?) {
        this.profileImageKey = key
    }

    fun changePassword(passwordHash: PasswordHash) {
        this.passwordHash = passwordHash
    }

    fun changeNickname(nickname: Nickname) {
        this.nickname = nickname
    }

    /** 이미 잠겨 있으면 그대로 둔다 — 최초 잠긴 시각을 보존한다. */
    fun lock(at: Instant) {
        if (lockedAt == null) lockedAt = at
    }

    fun unlock() {
        lockedAt = null
    }

    companion object {
        fun register(username: Username, passwordHash: PasswordHash, nickname: Nickname): User =
            User(
                id = null,
                username = username,
                passwordHash = passwordHash,
                nickname = nickname,
                profileImageKey = null,
                role = UserRole.USER,
                lockedAt = null,
                withdrawnAt = null,
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
            withdrawnAt: Instant? = null,
        ): User = User(
            id = id,
            username = username,
            passwordHash = passwordHash,
            nickname = nickname,
            profileImageKey = profileImageKey,
            role = role,
            lockedAt = lockedAt,
            withdrawnAt = withdrawnAt,
        )
    }
}
