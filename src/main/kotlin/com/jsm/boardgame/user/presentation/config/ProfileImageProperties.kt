package com.jsm.boardgame.user.presentation.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 프로필 이미지 키를 URL 로 조립하는 presentation 계층 설정. */
@ConfigurationProperties(prefix = "app.profile-image")
data class ProfileImageProperties(
    val baseUrl: String,
    val defaultUrl: String,
)
