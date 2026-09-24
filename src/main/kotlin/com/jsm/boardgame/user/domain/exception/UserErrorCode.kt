package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.error.ErrorCode
import com.jsm.boardgame.common.error.ErrorKind

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
    USER_ROLE_INVALID(ErrorKind.INVALID),

    // 인증. 아이디 없음과 비밀번호 틀림을 구분하지 않는다 (계정 열거 방지). ACCOUNT_LOCKED 는 예외다 —
    // 없는 아이디는 잠기지 않으므로 잠금 응답이 계정 존재를 드러낸다(수용한 트레이드오프).
    LOGIN_FAILED(ErrorKind.UNAUTHORIZED),
    REFRESH_TOKEN_INVALID(ErrorKind.UNAUTHORIZED),
    ACCOUNT_LOCKED(ErrorKind.UNAUTHORIZED),
    ;

    override val code: String get() = name
}
