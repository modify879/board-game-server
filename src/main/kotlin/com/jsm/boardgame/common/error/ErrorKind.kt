package com.jsm.boardgame.common.error

/** 오류의 추상 분류. HttpStatus 를 알지 않는다 — 도메인이 참조하는 타입이라 스프링이 들어오면 안 된다. */
enum class ErrorKind { INVALID, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT }
