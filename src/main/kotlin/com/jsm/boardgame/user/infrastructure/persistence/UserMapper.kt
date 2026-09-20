package com.jsm.boardgame.user.infrastructure.persistence

import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.ProfileImageKey
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.domain.model.Username

fun UserJpaEntity.toDomain(): User =
    User.reconstitute(
        id = UserId(id),
        username = Username.reconstitute(username),
        passwordHash = PasswordHash(passwordHash),
        nickname = Nickname.reconstitute(nickname),
        profileImageKey = profileImageKey?.let(ProfileImageKey::reconstitute),
        role = UserRole.valueOf(role),
    )

fun User.toJpaEntity(): UserJpaEntity =
    UserJpaEntity(
        id = id?.value ?: 0,
        username = username.value,
        passwordHash = passwordHash.value,
        nickname = nickname.value,
        profileImageKey = profileImageKey?.value,
        role = role.name,
    )
