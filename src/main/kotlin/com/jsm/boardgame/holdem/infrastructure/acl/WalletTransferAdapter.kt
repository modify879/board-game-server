package com.jsm.boardgame.holdem.infrastructure.acl

import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.wallet.application.command.usecase.TransferBalanceToGameCommand
import com.jsm.boardgame.wallet.application.command.usecase.TransferBalanceToGameUseCase
import com.jsm.boardgame.wallet.application.command.usecase.TransferFromGameCommand
import com.jsm.boardgame.wallet.application.command.usecase.TransferFromGameUseCase
import org.springframework.stereotype.Component

@Component
class WalletTransferAdapter(
    private val transferBalanceToGame: TransferBalanceToGameUseCase,
    private val transferFromGame: TransferFromGameUseCase,
) : WalletTransfer {
    override fun allToGame(userId: Long, tableId: Long, memo: String?): Long =
        transferBalanceToGame.transfer(TransferBalanceToGameCommand(userId, tableId, memo))

    override fun fromGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        transferFromGame.transfer(TransferFromGameCommand(userId, amount, tableId, memo))
    }
}
