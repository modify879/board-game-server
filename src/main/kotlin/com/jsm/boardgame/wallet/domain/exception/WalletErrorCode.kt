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
    ;

    override val code: String get() = name
}
