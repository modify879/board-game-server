package com.jsm.boardgame.user.domain.service

import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.RawPassword

fun interface PasswordHasher {
    fun hash(raw: RawPassword): PasswordHash
}
