package com.jsm.boardgame.common.persistence

import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException

/**
 * 예외 체인에서 Hibernate 의 ConstraintViolationException 을 먼저 찾아 `.constraintName` 을 쓴다.
 * 드라이버/커넥션 풀에 따라 래핑되지 않거나 제약 이름을 못 채워주는 경우가 있어,
 * 못 찾으면 예외 메시지 전체에서 후보 이름을 찾는다.
 * PostgreSQL 은 제약 이름을 소문자로 저장하므로 비교는 대소문자 무시로 한다.
 *
 * 반환값은 매칭된 후보 이름(인자로 준 철자 그대로)이거나 null 이다.
 */
fun DataIntegrityViolationException.violatedConstraint(vararg candidates: String): String? {
    val fromChain = generateSequence<Throwable>(this) { it.cause }
        .filterIsInstance<ConstraintViolationException>()
        .firstOrNull()
        ?.constraintName

    if (fromChain != null) {
        return candidates.firstOrNull { it.equals(fromChain, ignoreCase = true) }
    }

    val message = message.orEmpty()
    return candidates.firstOrNull { message.contains(it, ignoreCase = true) }
}
