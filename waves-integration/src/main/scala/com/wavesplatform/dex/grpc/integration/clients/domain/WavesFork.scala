package com.wavesplatform.dex.grpc.integration.clients.domain

import cats.instances.list._
import cats.instances.tuple._
import cats.syntax.foldable._
import com.google.protobuf.ByteString
import com.wavesplatform.dex.grpc.integration.clients.domain.WavesFork.Status

// TODO DEX-1009 Unit test
// TODO DEX-1011 This class is too slow for his purposes
case class WavesFork private[domain] (origChain: WavesChain, forkChain: WavesChain) {

  // TODO DEX-1009 Move to tests in the end

  require(!origChain.isEmpty, "empty origChain")

  // TODO DEX-1009 An additional invariant: forkChain should contain only one common block!

  def height: Int = forkChain.height

  def withBlock(block: WavesBlock): Status = forkChain.withBlock(block) match {
    case Left(e) => Status.Failed(withoutLast, e)
    case Right(updatedForkChain) =>
      // Compare heights to solve a situation when there are no transactions in the network since some height
      if (
        block.ref.height < origChain.height
        || block.tpe == WavesBlock.Type.FullBlock
        || block.tpe == WavesBlock.Type.MicroBlock && origChain.has(block.ref) // On the same chain
      ) Status.NotResolved(copy(forkChain = updatedForkChain))
      else {
        val (origDropped, forkDropped) = WavesChain.dropDifference(origChain, updatedForkChain)

        val origTxs = origDropped.foldLeft(Map.empty[ByteString, TransactionWithChanges])(_ ++ _.confirmedTxs)
        val forkTxs = forkDropped.foldLeft(Map.empty[ByteString, TransactionWithChanges])(_ ++ _.confirmedTxs)

        val origForkDiffIndex = origDropped.foldMap(_.diffIndex)
        val (updatedForkAllChanges, updatedForkDiffIndex) = forkDropped.foldMap(block => (block.changes, block.diffIndex))

        Status.Resolved(
          activeChain = updatedForkChain,
          // We should not filter it, because we can ask balances before a fork is resolved
          newChanges = updatedForkAllChanges, // TODO DEX-1011
          lostDiffIndex = origForkDiffIndex.without(updatedForkDiffIndex),
          lostTxIds = origTxs -- forkTxs.keys,
          confirmedTxs = forkTxs -- origTxs.keys
        )
      }
  }

  def withoutLast: WavesFork = copy(forkChain = forkChain.withoutLastLiquidOrFull)

  def rollbackTo(height: Int): WavesFork = copy(forkChain = forkChain.dropAfter(height)._1)
  def rollbackTo(ref: BlockRef): WavesFork = copy(forkChain = forkChain.dropAfter(ref)._1)

  override def toString: String = s"WavesFork(o=$origChain, f=$forkChain)"
}

object WavesFork {

  def mk(origChain: WavesChain, commonBlockRef: BlockRef): WavesFork = WavesFork(origChain, origChain.dropAfter(commonBlockRef)._1)
  def mk(origChain: WavesChain, commonHeight: Int): WavesFork = WavesFork(origChain, origChain.dropAfter(commonHeight)._1)

  def mkRolledBackByOne(origChain: WavesChain): WavesFork =
    WavesFork(origChain, origChain.withoutLastLiquidOrFull) // Or better use WavesFork.withoutLast

  sealed trait Status extends Product with Serializable

  object Status {

    case class Resolved(
      activeChain: WavesChain,
      newChanges: BlockchainBalance,
      lostDiffIndex: DiffIndex,
      lostTxIds: Map[ByteString, TransactionWithChanges], // Will be used in the future
      confirmedTxs: Map[ByteString, TransactionWithChanges]
    ) extends Status

    case class NotResolved(updatedFork: WavesFork) extends Status
    case class Failed(updatedFork: WavesFork, reason: String) extends Status
  }

}