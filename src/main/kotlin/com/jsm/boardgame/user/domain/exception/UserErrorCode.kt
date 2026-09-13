package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.ErrorCode
import com.jsm.boardgame.common.support.ErrorKind

enum class UserErrorCode(override val kind: ErrorKind) : ErrorCode {
    NICKNAME_LENGTH(ErrorKind.INVALID),
    NICKNAME_FORBIDDEN_CHARACTER(ErrorKind.INVALID),
    NICKNAME_BLANK(ErrorKind.INVALID),
    USERNAME_FORMAT(ErrorKind.INVALID),
    PASSWORD_TOO_SHORT(ErrorKind.INVALID),
    PASSWORD_CONFIRM_MISMATCH(ErrorKind.INVALID),
    PROFILE_IMAGE_KEY_INVALID(ErrorKind.INVALID),
    DUPLICATE_USERNAME(ErrorKind.CONFLICT),
    DUPLICATE_NICKNAME(ErrorKind.CONFLICT),
    USER_NOT_FOUND(ErrorKind.NOT_FOUND),
    ;

    override val code: String get() = name
}
