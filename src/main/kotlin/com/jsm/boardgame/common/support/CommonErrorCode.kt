package com.jsm.boardgame.common.support

/**
 * HTTP 수준의 기술 분류다. 어떤 바운디드 컨텍스트의 규칙도 담지 않는다 — 인증이 필요하다는
 * 사실과 권한이 없다는 사실은 도메인이 아니라 스프링 시큐리티 필터 단계에서 결정되므로
 * `common` 에 둬도 규칙 1(게임/컨텍스트 간 공유 금지)에 위배되지 않는다.
 */
enum class CommonErrorCode(override val kind: ErrorKind) : ErrorCode {
    AUTHENTICATION_REQUIRED(ErrorKind.UNAUTHORIZED),
    ACCESS_DENIED(ErrorKind.FORBIDDEN),
    ;

    override val code: String get() = name
}
