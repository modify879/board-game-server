package com.jsm.boardgame.common.error

/** HTTP 수준의 기술 분류다. 어떤 바운디드 컨텍스트의 규칙도 담지 않는다 (규칙 8). */
enum class CommonErrorCode(override val kind: ErrorKind) : ErrorCode {
    AUTHENTICATION_REQUIRED(ErrorKind.UNAUTHORIZED),
    ACCESS_DENIED(ErrorKind.FORBIDDEN),
    ;

    override val code: String get() = name
}
