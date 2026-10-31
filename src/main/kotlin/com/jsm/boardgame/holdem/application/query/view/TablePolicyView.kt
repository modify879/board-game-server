package com.jsm.boardgame.holdem.application.query.view

/** 테이블 행이 들고 있는 정책 컬럼. 도메인 [com.jsm.boardgame.holdem.domain.model.TablePolicy] 로의 복원은 조회 서비스가 한다. */
data class TablePolicyView(val smallBlind: Long, val bigBlind: Long, val minBuyInBigBlinds: Int, val maxBuyInBigBlinds: Int)
