package com.jsm.boardgame.wallet.application.command.usecase

/**
 * 회원 탈퇴가 호출자 트랜잭션 안에서 지갑 행을 잠그고 비었는지 확인한다. 잠금은 호출자가 커밋할 때까지 유지돼,
 * 그동안 이 사용자의 바이인·충전·환전 요청은 지갑 락에서 기다렸다가 탈퇴가 커밋되면 활성 확인에서 떨어진다.
 *
 * 비었는지만 돌려준다 — "비어 있어야 탈퇴할 수 있다" 는 user 의 규칙이라 오류 코드도 user 가 고른다(규칙 8).
 */
interface LockWalletForAccountClosureUseCase {
    /** 잔액이 0 이고 처리 대기 중인 충전·환전 요청이 없으면 true. 트랜잭션 밖에서 부르면 실패한다. */
    fun lockAndCheckEmpty(userId: Long): Boolean
}
