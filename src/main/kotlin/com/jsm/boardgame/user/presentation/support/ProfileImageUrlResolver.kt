package com.jsm.boardgame.user.presentation.support

import com.jsm.boardgame.user.presentation.config.ProfileImageProperties

import org.springframework.stereotype.Component

@Component
class ProfileImageUrlResolver(private val properties: ProfileImageProperties) {

    fun resolve(key: String): String {
        val base = properties.baseUrl.trimEnd('/')
        val path = key.trimStart('/')
        return "$base/$path"
    }
}
