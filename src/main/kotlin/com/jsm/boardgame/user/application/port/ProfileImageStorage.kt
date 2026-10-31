package com.jsm.boardgame.user.application.port

import com.jsm.boardgame.user.domain.model.ProfileImageKey
import com.jsm.boardgame.user.domain.model.ProfileImageType
import com.jsm.boardgame.user.domain.model.UserId

interface ProfileImageStorage {
    /** 새 파일을 저장하고 그 키를 돌려준다. 같은 사용자도 매번 다른 키를 받는다. */
    fun store(userId: UserId, bytes: ByteArray, type: ProfileImageType): ProfileImageKey

    /** 없는 파일이면 아무 일도 하지 않는다. */
    fun delete(key: ProfileImageKey)
}
