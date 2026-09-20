package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.ErrorCode
import com.jsm.boardgame.common.support.ErrorKind

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
    ;

    override val code: String get() = name
}
