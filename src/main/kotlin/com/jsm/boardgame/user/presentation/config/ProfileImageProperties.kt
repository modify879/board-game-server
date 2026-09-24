package com.jsm.boardgame.user.presentation.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.profile-image")
data class ProfileImageProperties(
    val baseUrl: String,
    val defaultUrl: String,
)
