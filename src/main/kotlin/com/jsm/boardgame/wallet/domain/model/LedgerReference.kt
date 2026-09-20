package com.jsm.boardgame.wallet.domain.model

enum class LedgerReferenceType { DEPOSIT_REQUEST, WITHDRAWAL_REQUEST, ADMIN_ADJUSTMENT }

// ADMIN_ADJUSTMENT 일 때 id 는 조정을 수행한 관리자의 userId 다.
data class LedgerReference(val type: LedgerReferenceType, val id: Long)
