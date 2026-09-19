package com.jsm.boardgame.user.presentation.config

import org.springframework.stereotype.Component

@Component
class ProfileImageUrlResolver(private val properties: ProfileImageProperties) {

    fun resolve(key: String?): String {
        if (key == null) return properties.defaultUrl
        val base = properties.baseUrl.trimEnd('/')
        val path = key.trimStart('/')
        return "$base/$path"
    }
}
