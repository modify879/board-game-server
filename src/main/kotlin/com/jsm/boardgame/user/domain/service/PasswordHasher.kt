package com.jsm.boardgame.user.domain.service

import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.RawPassword

/**
 * 해싱은 기술 관심사이므로 도메인은 포트로 주입받는다.
 *
 * [matches] 가 필요한 이유: BCrypt 는 해시에 솔트가 들어 있어 같은 비밀번호라도
 * 해시가 매번 다르다. `hash(raw) == storedHash` 비교는 성립하지 않는다.
 */
interface PasswordHasher {
    fun hash(raw: RawPassword): PasswordHash

    /**
     * [hash] 가 null 이면 더미 해시로 비교하고 false 를 돌려준다.
     * 사용자가 없을 때도 같은 시간이 걸리게 해 응답 시간으로 계정 존재 여부를 알아내지 못하게 한다.
     * 더미 해시는 알고리즘을 아는 구현체가 소유한다 — 도메인은 BCrypt 를 모른다.
     */
    fun matches(raw: RawPassword, hash: PasswordHash?): Boolean
}
