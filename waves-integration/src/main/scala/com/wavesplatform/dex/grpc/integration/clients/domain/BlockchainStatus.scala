package com.wavesplatform.dex.grpc.integration.clients.domain

import com.wavesplatform.dex.grpc.integration.clients.domain.WavesNodeEvent.WavesNodeUtxEvent
import com.wavesplatform.dex.meta.getSimpleName

import scala.collection.immutable.Queue

sealed trait BlockchainStatus extends Product with Serializable {
  def name: String = getSimpleName(this)
}

object BlockchainStatus {

  case class Normal(main: WavesChain) extends BlockchainStatus {
    override def toString: String = s"Normal(${main.history.headOption.map(_.ref)})"
  }

  case class TransientRollback(fork: WavesFork, utxEventsStash: Queue[WavesNodeUtxEvent]) extends BlockchainStatus {
    override def toString: String = s"TransientRollback(f=$fork, utx=${utxEventsStash.size})"
  }

  case class TransientResolving(
                                 main: WavesChain,
                                 stashChanges: BlockchainBalance,
                                 utxEventsStash: Queue[WavesNodeUtxEvent]
  ) extends BlockchainStatus {

    override def toString: String =
      s"TransientResolving(${main.history.headOption.map(_.ref)}, u=${utxEventsStash.length})"

  }

}
