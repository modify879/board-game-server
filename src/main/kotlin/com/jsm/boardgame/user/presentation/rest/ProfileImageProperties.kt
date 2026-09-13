package com.jsm.boardgame.user.presentation.rest

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * CDN 도메인은 인프라 설정이다. 도메인 모델은 프로필 이미지의 "키"만 알아야 하고,
 * 그 키를 실제로 접근 가능한 URL 로 조립하는 책임은 이 presentation 계층 설정이 진다.
 * base-url 이 바뀌어도(CDN 이관 등) 도메인/애플리케이션 계층은 전혀 영향받지 않는다.
 */
@ConfigurationProperties(prefix = "app.profile-image")
data class ProfileImageProperties(
    val baseUrl: String,
    val defaultUrl: String,
)
