package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.common.error.AuthenticationRequiredException
import com.jsm.boardgame.user.application.command.usecase.ChangeNicknameUseCase
import com.jsm.boardgame.user.application.command.usecase.ChangePasswordUseCase
import com.jsm.boardgame.user.application.command.usecase.ChangeProfileImageCommand
import com.jsm.boardgame.user.application.command.usecase.ChangeProfileImageUseCase
import com.jsm.boardgame.user.application.command.usecase.RemoveProfileImageUseCase
import com.jsm.boardgame.user.application.command.usecase.WithdrawUseCase
import com.jsm.boardgame.user.application.command.usecase.SignUpUseCase
import com.jsm.boardgame.user.application.query.service.UserQueryService
import com.jsm.boardgame.user.presentation.support.ProfileImageUrlResolver
import com.jsm.boardgame.user.presentation.rest.request.ChangeNicknameRequest
import com.jsm.boardgame.user.presentation.rest.request.ChangePasswordRequest
import com.jsm.boardgame.user.presentation.rest.request.SignUpRequest
import com.jsm.boardgame.user.presentation.rest.request.WithdrawRequest
import com.jsm.boardgame.user.presentation.rest.response.ProfileImageResponse
import com.jsm.boardgame.user.presentation.rest.response.TokenResponse
import com.jsm.boardgame.user.presentation.rest.response.UserProfileResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.URI
import java.time.Clock
import java.time.Duration

@RestController
@RequestMapping("/api/users")
class UserController(
    private val signUpUseCase: SignUpUseCase,
    private val changeNicknameUseCase: ChangeNicknameUseCase,
    private val changePasswordUseCase: ChangePasswordUseCase,
    private val changeProfileImageUseCase: ChangeProfileImageUseCase,
    private val removeProfileImageUseCase: RemoveProfileImageUseCase,
    private val withdrawUseCase: WithdrawUseCase,
    private val userQueryService: UserQueryService,
    private val profileImageUrlResolver: ProfileImageUrlResolver,
    private val clock: Clock,
) {

    @PostMapping
    fun signUp(@RequestBody request: SignUpRequest): ResponseEntity<Unit> {
        val id = signUpUseCase.signUp(request.toCommand())
        return ResponseEntity.created(URI.create("/api/users/$id")).build()
    }

    @GetMapping("/{id}")
    fun getProfile(@PathVariable id: Long): UserProfileResponse {
        val profile = userQueryService.findProfile(id)
        return UserProfileResponse.from(profile, profile.profileImageKey?.let(profileImageUrlResolver::resolve))
    }

    @PatchMapping("/me/nickname")
    fun changeNickname(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: ChangeNicknameRequest,
    ): ResponseEntity<Void> {
        changeNicknameUseCase.changeNickname(request.toCommand(userIdOf(jwt)))
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/me/password")
    fun changePassword(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: ChangePasswordRequest,
    ): ResponseEntity<TokenResponse> =
        tokensResponse(changePasswordUseCase.changePassword(request.toCommand(userIdOf(jwt))), clock)

    @PutMapping("/me/profile-image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun changeProfileImage(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam("file") file: MultipartFile,
    ): ProfileImageResponse {
        val key = changeProfileImageUseCase.change(ChangeProfileImageCommand(userIdOf(jwt), file.bytes))
        return ProfileImageResponse(profileImageUrlResolver.resolve(key))
    }

    @DeleteMapping("/me/profile-image")
    fun removeProfileImage(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<Void> {
        removeProfileImageUseCase.remove(userIdOf(jwt))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/me/withdrawal")
    fun withdraw(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: WithdrawRequest,
    ): ResponseEntity<Void> {
        withdrawUseCase.withdraw(request.toCommand(userIdOf(jwt)))
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, refreshTokenCookie("", Duration.ZERO).toString())
            .build()
    }

    private fun userIdOf(jwt: Jwt): Long =
        jwt.subject?.toLongOrNull()
            ?: throw AuthenticationRequiredException("인증된 JWT 의 subject 를 사용자 식별자로 파싱할 수 없다: subject=${jwt.subject}")
}
