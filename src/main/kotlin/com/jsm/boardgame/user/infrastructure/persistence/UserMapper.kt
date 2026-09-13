package com.jsm.boardgame.user.infrastructure.persistence

import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.ProfileImageKey
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.Username

fun UserJpaEntity.toDomain(): User =
    User.restore(
        id = UserId(id),
        username = Username.restore(username),
        passwordHash = PasswordHash(passwordHash),
        nickname = Nickname.restore(nickname),
        profileImageKey = profileImageKey?.let(ProfileImageKey::restore),
    )

fun User.toJpaEntity(): UserJpaEntity =
    UserJpaEntity(
        id = id?.value ?: 0,
        username = username.value,
        passwordHash = passwordHash.value,
        nickname = nickname.value,
        profileImageKey = profileImageKey?.value,
    )
