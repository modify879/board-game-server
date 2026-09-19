package com.jsm.boardgame.user.domain.model

class User private constructor(
    val id: UserId?,
    val username: Username,
    passwordHash: PasswordHash,
    nickname: Nickname,
    profileImageKey: ProfileImageKey?,
    role: UserRole,
) {
    var passwordHash: PasswordHash = passwordHash
        private set
    var nickname: Nickname = nickname
        private set
    var profileImageKey: ProfileImageKey? = profileImageKey
        private set
    var role: UserRole = role
        private set

    fun changeRole(role: UserRole) {
        this.role = role
    }

    companion object {
        /** 신규 가입. 프로필 이미지는 항상 null 로 시작한다(= 기본 프로필). 역할은 항상 USER 로 시작한다. */
        fun register(username: Username, passwordHash: PasswordHash, nickname: Nickname): User =
            User(
                id = null,
                username = username,
                passwordHash = passwordHash,
                nickname = nickname,
                profileImageKey = null,
                role = UserRole.USER,
            )

        /** 영속 복원 전용 — 검증하지 않는다. */
        fun reconstitute(
            id: UserId,
            username: Username,
            passwordHash: PasswordHash,
            nickname: Nickname,
            profileImageKey: ProfileImageKey?,
            role: UserRole,
        ): User = User(
            id = id,
            username = username,
            passwordHash = passwordHash,
            nickname = nickname,
            profileImageKey = profileImageKey,
            role = role,
        )
    }
}
