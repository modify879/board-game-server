package com.jsm.boardgame.user.domain.model

class User private constructor(
    val id: UserId?,
    val username: Username,
    passwordHash: PasswordHash,
    nickname: Nickname,
    profileImageKey: ProfileImageKey?,
) {
    var passwordHash: PasswordHash = passwordHash
        private set
    var nickname: Nickname = nickname
        private set
    var profileImageKey: ProfileImageKey? = profileImageKey
        private set

    companion object {
        /** 신규 가입. 프로필 이미지는 항상 null 로 시작한다(= 기본 프로필). */
        fun register(username: Username, passwordHash: PasswordHash, nickname: Nickname): User =
            User(id = null, username = username, passwordHash = passwordHash, nickname = nickname, profileImageKey = null)

        /** 영속 계층에서 복원할 때만 쓴다. DDD 문헌의 reconstitution 에 해당한다. */
        fun reconstitute(
            id: UserId,
            username: Username,
            passwordHash: PasswordHash,
            nickname: Nickname,
            profileImageKey: ProfileImageKey?,
        ): User = User(id = id, username = username, passwordHash = passwordHash, nickname = nickname, profileImageKey = profileImageKey)
    }
}
