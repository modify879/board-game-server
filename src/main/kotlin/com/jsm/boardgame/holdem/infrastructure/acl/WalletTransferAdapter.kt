package com.jsm.boardgame.holdem.infrastructure.acl

import com.jsm.boardgame.holdem.application.port.WalletTransfer
import com.jsm.boardgame.wallet.application.command.usecase.TransferFromGameCommand
import com.jsm.boardgame.wallet.application.command.usecase.TransferFromGameUseCase
import com.jsm.boardgame.wallet.application.command.usecase.TransferToGameCommand
import com.jsm.boardgame.wallet.application.command.usecase.TransferToGameUseCase
import org.springframework.stereotype.Component

@Component
class WalletTransferAdapter(
    private val transferToGame: TransferToGameUseCase,
    private val transferFromGame: TransferFromGameUseCase,
) : WalletTransfer {
    override fun toGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        transferToGame.transfer(TransferToGameCommand(userId, amount, tableId, memo))
    }

    override fun fromGame(userId: Long, amount: Long, tableId: Long, memo: String?) {
        transferFromGame.transfer(TransferFromGameCommand(userId, amount, tableId, memo))
    }
}
