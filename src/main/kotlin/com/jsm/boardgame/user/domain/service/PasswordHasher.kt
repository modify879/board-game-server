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

    fun matches(raw: RawPassword, hash: PasswordHash): Boolean
}
