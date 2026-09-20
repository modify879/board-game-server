package com.jsm.boardgame.wallet.presentation.rest

import com.jsm.boardgame.common.support.AuthenticationRequiredException
import com.jsm.boardgame.wallet.application.command.CancelDepositRequestCommand
import com.jsm.boardgame.wallet.application.command.CancelDepositRequestUseCase
import com.jsm.boardgame.wallet.application.command.CancelWithdrawalRequestCommand
import com.jsm.boardgame.wallet.application.command.CancelWithdrawalRequestUseCase
import com.jsm.boardgame.wallet.application.command.RequestDepositUseCase
import com.jsm.boardgame.wallet.application.command.RequestWithdrawalUseCase
import com.jsm.boardgame.wallet.application.query.DepositRequestQueryService
import com.jsm.boardgame.wallet.application.query.WalletQueryService
import com.jsm.boardgame.wallet.application.query.WithdrawalRequestQueryService
import com.jsm.boardgame.wallet.presentation.config.DepositAccountResolver
import com.jsm.boardgame.wallet.presentation.rest.request.RequestDepositRequest
import com.jsm.boardgame.wallet.presentation.rest.request.RequestWithdrawalRequest
import com.jsm.boardgame.wallet.presentation.rest.response.DepositRequestCreatedResponse
import com.jsm.boardgame.wallet.presentation.rest.response.DepositRequestResponse
import com.jsm.boardgame.wallet.presentation.rest.response.LedgerEntryResponse
import com.jsm.boardgame.wallet.presentation.rest.response.WalletBalanceResponse
import com.jsm.boardgame.wallet.presentation.rest.response.WithdrawalRequestCreatedResponse
import com.jsm.boardgame.wallet.presentation.rest.response.WithdrawalRequestResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/wallet")
class WalletController(
    private val requestDepositUseCase: RequestDepositUseCase,
    private val cancelDepositRequestUseCase: CancelDepositRequestUseCase,
    private val requestWithdrawalUseCase: RequestWithdrawalUseCase,
    private val cancelWithdrawalRequestUseCase: CancelWithdrawalRequestUseCase,
    private val walletQueryService: WalletQueryService,
    private val depositRequestQueryService: DepositRequestQueryService,
    private val withdrawalRequestQueryService: WithdrawalRequestQueryService,
    private val depositAccountResolver: DepositAccountResolver,
) {

    @GetMapping
    fun getBalance(@AuthenticationPrincipal jwt: Jwt): WalletBalanceResponse =
        WalletBalanceResponse.from(walletQueryService.findBalance(jwt.requireUserId()))

    @GetMapping("/ledger")
    fun getLedger(@AuthenticationPrincipal jwt: Jwt, pageable: Pageable): Page<LedgerEntryResponse> =
        walletQueryService.findLedger(jwt.requireUserId(), pageable).map(LedgerEntryResponse::from)

    @PostMapping("/deposit-requests")
    @ResponseStatus(HttpStatus.CREATED)
    fun requestDeposit(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: RequestDepositRequest,
    ): DepositRequestCreatedResponse {
        val requestId = requestDepositUseCase.request(request.toCommand(jwt.requireUserId()))
        return DepositRequestCreatedResponse.from(requestId.value, depositAccountResolver.resolve())
    }

    @GetMapping("/deposit-requests")
    fun getMyDepositRequests(@AuthenticationPrincipal jwt: Jwt, pageable: Pageable): Page<DepositRequestResponse> =
        depositRequestQueryService.findMine(jwt.requireUserId(), pageable).map(DepositRequestResponse::from)

    @DeleteMapping("/deposit-requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelDepositRequest(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long) {
        cancelDepositRequestUseCase.cancel(CancelDepositRequestCommand(requestId = id, requesterUserId = jwt.requireUserId()))
    }

    @PostMapping("/withdrawal-requests")
    @ResponseStatus(HttpStatus.CREATED)
    fun requestWithdrawal(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: RequestWithdrawalRequest,
    ): WithdrawalRequestCreatedResponse {
        val requestId = requestWithdrawalUseCase.request(request.toCommand(jwt.requireUserId()))
        return WithdrawalRequestCreatedResponse.from(requestId.value)
    }

    @GetMapping("/withdrawal-requests")
    fun getMyWithdrawalRequests(@AuthenticationPrincipal jwt: Jwt, pageable: Pageable): Page<WithdrawalRequestResponse> =
        withdrawalRequestQueryService.findMine(jwt.requireUserId(), pageable).map(WithdrawalRequestResponse::from)

    @DeleteMapping("/withdrawal-requests/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancelWithdrawalRequest(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: Long) {
        cancelWithdrawalRequestUseCase.cancel(
            CancelWithdrawalRequestCommand(requestId = id, requesterUserId = jwt.requireUserId()),
        )
    }
}

// AuthController.logout() 과 같은 방식이다 — subject 가 숫자로 파싱되지 않으면 401(오류 계약)로 변환한다.
private fun Jwt.requireUserId(): Long =
    subject?.toLongOrNull()
        ?: throw AuthenticationRequiredException("인증된 JWT 의 subject 를 사용자 식별자로 파싱할 수 없다: subject=$subject")
