package com.jsm.boardgame.wallet.infrastructure.persistence

import jakarta.persistence.CheckConstraint
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version

@Entity
@Table(
    name = "wallets",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_wallets_user", columnNames = ["user_id"]),
    ],
    check = [
        CheckConstraint(name = "ck_wallets_balance_non_negative", constraint = "balance >= 0"),
    ],
)
class WalletJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "balance", nullable = false)
    var balance: Long,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
