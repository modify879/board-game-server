package com.jsm.boardgame.user.presentation.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Paths

/** 업로드된 프로필 이미지를 저장 디렉터리에서 그대로 서빙한다. 저장 쪽은 `LocalProfileImageStorage`. */
@Configuration
class ProfileImageResourceConfig(
    @Value("\${app.profile-image.storage-dir}") private val storageDir: String,
) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // 끝의 '/' 가 없으면 디렉터리가 아니라 파일 경로로 해석된다.
        val location = Paths.get(storageDir).toAbsolutePath().normalize().toUri().toString().trimEnd('/') + "/"
        registry.addResourceHandler("/images/profiles/**").addResourceLocations(location)
    }
}
