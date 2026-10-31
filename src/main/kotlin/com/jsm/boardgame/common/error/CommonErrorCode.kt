package com.jsm.boardgame.common.error

/** HTTP 수준의 기술 분류다. 어떤 바운디드 컨텍스트의 규칙도 담지 않는다 (규칙 8). */
enum class CommonErrorCode(override val kind: ErrorKind) : ErrorCode {
    AUTHENTICATION_REQUIRED(ErrorKind.UNAUTHORIZED),
    ACCESS_DENIED(ErrorKind.FORBIDDEN),
    TEMPORARILY_UNAVAILABLE(ErrorKind.UNAVAILABLE),
    INTERNAL_ERROR(ErrorKind.INTERNAL),
    // 스프링 MVC 가 직접 던지는 4xx(잘못된 JSON, 미지원 메서드 등). GlobalExceptionHandler 가 쓴다.
    REQUEST_INVALID(ErrorKind.INVALID),
    ;

    override val code: String get() = name
}
