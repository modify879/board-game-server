package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.ErrorCode
import com.jsm.boardgame.common.support.ErrorKind

enum class UserErrorCode(override val kind: ErrorKind) : ErrorCode {
    NICKNAME_LENGTH(ErrorKind.INVALID),
    NICKNAME_FORBIDDEN_CHARACTER(ErrorKind.INVALID),
    NICKNAME_BLANK(ErrorKind.INVALID),
    USERNAME_FORMAT(ErrorKind.INVALID),
    PASSWORD_TOO_SHORT(ErrorKind.INVALID),
    PASSWORD_TOO_LONG(ErrorKind.INVALID),
    PASSWORD_CONFIRM_MISMATCH(ErrorKind.INVALID),
    PROFILE_IMAGE_KEY_INVALID(ErrorKind.INVALID),
    DUPLICATE_USERNAME(ErrorKind.CONFLICT),
    DUPLICATE_NICKNAME(ErrorKind.CONFLICT),
    USER_NOT_FOUND(ErrorKind.NOT_FOUND),

    // 인증. 아이디가 없는 것과 비밀번호가 틀린 것을 구분하지 않는다 —
    // 구분하면 어떤 아이디가 존재하는지 알아낼 수 있다(계정 열거).
    LOGIN_FAILED(ErrorKind.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(ErrorKind.UNAUTHORIZED),
    ;

    override val code: String get() = name
}
