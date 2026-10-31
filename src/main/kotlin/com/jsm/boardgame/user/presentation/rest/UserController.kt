package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.common.error.AuthenticationRequiredException
import com.jsm.boardgame.user.application.command.usecase.ChangeNicknameUseCase
import com.jsm.boardgame.user.application.command.usecase.SignUpUseCase
import com.jsm.boardgame.user.application.query.service.UserQueryService
import com.jsm.boardgame.user.presentation.config.ProfileImageUrlResolver
import com.jsm.boardgame.user.presentation.rest.request.ChangeNicknameRequest
import com.jsm.boardgame.user.presentation.rest.request.SignUpRequest
import com.jsm.boardgame.user.presentation.rest.response.UserProfileResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/users")
class UserController(
    private val signUpUseCase: SignUpUseCase,
    private val changeNicknameUseCase: ChangeNicknameUseCase,
    private val userQueryService: UserQueryService,
    private val profileImageUrlResolver: ProfileImageUrlResolver,
) {

    @PostMapping
    fun signUp(@RequestBody request: SignUpRequest): ResponseEntity<Unit> {
        val id = signUpUseCase.signUp(request.toCommand())
        return ResponseEntity.created(URI.create("/api/users/$id")).build()
    }

    @GetMapping("/{id}")
    fun getProfile(@PathVariable id: Long): UserProfileResponse {
        val profile = userQueryService.findProfile(id)
        return UserProfileResponse.from(profile, profileImageUrlResolver.resolve(profile.profileImageKey))
    }

    @PatchMapping("/me/nickname")
    fun changeNickname(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: ChangeNicknameRequest,
    ): ResponseEntity<Void> {
        val userId = jwt.subject?.toLongOrNull()
            ?: throw AuthenticationRequiredException("인증된 JWT 의 subject 를 사용자 식별자로 파싱할 수 없다: subject=${jwt.subject}")
        changeNicknameUseCase.changeNickname(request.toCommand(userId))
        return ResponseEntity.noContent().build()
    }
}
