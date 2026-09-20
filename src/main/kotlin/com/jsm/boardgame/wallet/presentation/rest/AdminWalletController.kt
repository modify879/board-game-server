package com.jsm.boardgame.wallet.presentation.rest

import com.jsm.boardgame.common.support.AuthenticationRequiredException
import com.jsm.boardgame.wallet.application.command.AdjustWalletBalanceUseCase
import com.jsm.boardgame.wallet.application.command.ApproveDepositRequestUseCase
import com.jsm.boardgame.wallet.application.command.ApproveWithdrawalRequestCommand
import com.jsm.boardgame.wallet.application.command.ApproveWithdrawalRequestUseCase
import com.jsm.boardgame.wallet.application.command.RejectDepositRequestUseCase
import com.jsm.boardgame.wallet.application.command.RejectWithdrawalRequestUseCase
import com.jsm.boardgame.wallet.application.query.DepositRequestQueryService
import com.jsm.boardgame.wallet.application.query.WithdrawalRequestQueryService
import com.jsm.boardgame.wallet.domain.model.DepositRequestStatus
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestStatus
import com.jsm.boardgame.wallet.presentation.rest.request.AdjustWalletBalanceRequest
import com.jsm.boardgame.wallet.presentation.rest.request.ApproveDepositRequestRequest
import com.jsm.boardgame.wallet.presentation.rest.request.RejectDepositRequestRequest
import com.jsm.boardgame.wallet.presentation.rest.request.RejectWithdrawalRequestRequest
import com.jsm.boardgame.wallet.presentation.rest.response.DepositRequestResponse
import com.jsm.boardgame.wallet.presentation.rest.response.WithdrawalRequestResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// 인가는 SecurityConfig 의 "/api/admin/**" → hasRole("ADMIN") 이 이미 처리한다 (AdminUserController 와 같은 이유로 @PreAuthorize 를 덧붙이지 않는다).
@RestController
@RequestMapping("/api/admin")
class AdminWalletController(
    private val approveDepositRequestUseCase: ApproveDepositRequestUseCase,
    private val rejectDepositRequestUseCase: RejectDepositRequestUseCase,
    private val approveWithdrawalRequestUseCase: ApproveWithdrawalRequestUseCase,
    private val rejectWithdrawalRequestUseCase: RejectWithdrawalRequestUseCase,
    private val adjustWalletBalanceUseCase: AdjustWalletBalanceUseCase,
    private val depositRequestQueryService: DepositRequestQueryService,
    private val withdrawalRequestQueryService: WithdrawalRequestQueryService,
) {

    @GetMapping("/deposit-requests")
    fun getDepositRequests(
        @RequestParam(required = false) status: DepositRequestStatus?,
        pageable: Pageable,
    ): Page<DepositRequestResponse> =
        depositRequestQueryService.findByStatus(status, pageable).map(DepositRequestResponse::from)

    @PostMapping("/deposit-requests/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun approveDepositRequest(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: Long,
        @RequestBody(required = false) request: ApproveDepositRequestRequest?,
    ) {
        val command = (request ?: ApproveDepositRequestRequest()).toCommand(id, jwt.requireUserId())
        approveDepositRequestUseCase.approve(command)
    }

    @PostMapping("/deposit-requests/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun rejectDepositRequest(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: Long,
        @RequestBody request: RejectDepositRequestRequest,
    ) {
        rejectDepositRequestUseCase.reject(request.toCommand(id, jwt.requireUserId()))
    }

    @GetMapping("/withdrawal-requests")
    fun getWithdrawalRequests(
        @RequestParam(required = false) status: WithdrawalRequestStatus?,
        pageable: Pageable,
    ): Page<WithdrawalRequestResponse> =
        withdrawalRequestQueryService.findByStatus(status, pageable).map(WithdrawalRequestResponse::from)

    @PostMapping("/withdrawal-requests/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun approveWithdrawalRequest(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long) {
        approveWithdrawalRequestUseCase.approve(ApproveWithdrawalRequestCommand(requestId = id, adminUserId = jwt.requireUserId()))
    }

    @PostMapping("/withdrawal-requests/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun rejectWithdrawalRequest(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: Long,
        @RequestBody request: RejectWithdrawalRequestRequest,
    ) {
        rejectWithdrawalRequestUseCase.reject(request.toCommand(id, jwt.requireUserId()))
    }

    @PostMapping("/wallets/{userId}/adjustments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun adjustBalance(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable userId: Long,
        @RequestBody request: AdjustWalletBalanceRequest,
    ) {
        adjustWalletBalanceUseCase.adjust(request.toCommand(userId, jwt.requireUserId()))
    }
}

// AuthController.logout() 과 같은 방식이다 — subject 가 숫자로 파싱되지 않으면 401(오류 계약)로 변환한다.
private fun Jwt.requireUserId(): Long =
    subject?.toLongOrNull()
        ?: throw AuthenticationRequiredException("인증된 JWT 의 subject 를 사용자 식별자로 파싱할 수 없다: subject=$subject")
