package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.error.ErrorCode
import com.jsm.boardgame.common.error.ErrorKind

enum class WalletErrorCode(override val kind: ErrorKind) : ErrorCode {
    AMOUNT_NEGATIVE(ErrorKind.INVALID),
    AMOUNT_NOT_POSITIVE(ErrorKind.INVALID),
    INSUFFICIENT_BALANCE(ErrorKind.CONFLICT),
    CONCURRENT_WALLET_UPDATE(ErrorKind.CONFLICT),
    DEPOSIT_AMOUNT_INVALID(ErrorKind.INVALID),
    REJECTION_REASON_BLANK(ErrorKind.INVALID),
    DEPOSIT_REQUEST_NOT_FOUND(ErrorKind.NOT_FOUND),
    DEPOSIT_REQUEST_ALREADY_PROCESSED(ErrorKind.CONFLICT),
    NOT_REQUEST_OWNER(ErrorKind.FORBIDDEN),
    WITHDRAWAL_AMOUNT_INVALID(ErrorKind.INVALID),
    BANK_ACCOUNT_INVALID(ErrorKind.INVALID),
    ADJUSTMENT_AMOUNT_INVALID(ErrorKind.INVALID),
    ADJUSTMENT_REASON_BLANK(ErrorKind.INVALID),
    WITHDRAWAL_REQUEST_NOT_FOUND(ErrorKind.NOT_FOUND),
    WITHDRAWAL_REQUEST_ALREADY_PROCESSED(ErrorKind.CONFLICT),
    // 없는 리소스에 접근한 게 아니라 관리자가 보낸 요청의 대상이 틀린 것이므로 NOT_FOUND 가 아니라 INVALID(400)다.
    // UserExistence 사전 체크와 fk_wallets_user 위반이 둘 다 이 코드로 떨어진다 — 조정 전용 이름이면
    // 어댑터가 환전·충전 경로의 FK 위반에도 "조정" 코드를 내보내게 된다.
    WALLET_OWNER_NOT_FOUND(ErrorKind.INVALID),
    // Money.of() 는 음수만 막고 100원 단위는 강제하지 않는다(다른 용도로도 쓰이는 범용 VO라서).
    // 바이인·캐시아웃도 DepositRequest/WithdrawalRequest 처럼 단위를 스스로 검사해야 하는데,
    // 게임 이체엔 영속 애그리거트가 없어 그 검사를 둘 도메인 팩토리가 없다. 바이인·캐시아웃은
    // 방향만 다르고 검사 내용은 같아서(Adjustment 가 credit/debit 을 코드 하나로 묶는 것과 같은 이유)
    // 코드 하나를 공유한다.
    GAME_TRANSFER_AMOUNT_INVALID(ErrorKind.INVALID),

    // 같은 Idempotency-Key 로 관리자 조정을 두 번 요청함 — wallet_adjustment_keys 의 PK 위반을 이 코드로 번역한다.
    ADJUSTMENT_ALREADY_APPLIED(ErrorKind.CONFLICT),
    // Idempotency-Key 헤더가 없거나 비었거나 100 코드포인트를 넘음.
    IDEMPOTENCY_KEY_INVALID(ErrorKind.INVALID),
    ;

    override val code: String get() = name
}
