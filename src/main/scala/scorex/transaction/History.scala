package scorex.transaction

import scorex.account.Account
import scorex.block.Block
import scorex.block.Block.BlockId
import scorex.crypto.encode.Base58
import scorex.network.Checkpoint

import scala.util.Try

/**
  * History of a blockchain system is some blocktree in fact(like this: http://image.slidesharecdn.com/sfbitcoindev-chepurnoy-2015-150322043044-conversion-gate01/95/proofofstake-its-improvements-san-francisco-bitcoin-devs-hackathon-12-638.jpg),
  * where longest chain is being considered as canonical one, containing right kind of history.
  *
  * In cryptocurrencies of today blocktree view is usually implicit, means code supports only linear history,
  * but other options are possible.
  *
  * To say "longest chain" is the canonical one is simplification, usually some kind of "cumulative difficulty"
  * function has been used instead, even in PoW systems.
  */

trait History {

  import scorex.transaction.History.BlockchainScore

  /**
    * Height of the a chain, or a longest chain in the explicit block-tree
    */
  def height(): Int

  def blockAt(height: Int): Option[Block]

  /**
    * Quality score of a best chain, e.g. cumulative difficulty in case of Bitcoin / Nxt
    *
    * @return
    */
  def score(): BlockchainScore

  def scoreOf(id: BlockId): BlockchainScore

  /**
    * Is there's no history, even genesis block
    *
    * @return
    */
  def isEmpty: Boolean = height() == 0

  def contains(block: Block): Boolean = contains(block.uniqueId)

  def contains(id: BlockId): Boolean = blockById(id).isDefined

  def blockById(blockId: BlockId): Option[Block]

  def blockById(blockId: String): Option[Block] = Base58.decode(blockId).toOption.flatMap(blockById)

  /**
    * Height of a block if it's in the blocktree
    */
  def heightOf(block: Block): Option[Int] = heightOf(block.uniqueId)

  def heightOf(blockId: BlockId): Option[Int]

  /**
    * Use BlockStorage.appendBlock(block: Block) if you want to automatically update state
    *
    * Append block to a chain, based on it's reference
    *
    * @param block - block to append
    * @return Blocks to process in state
    */
  def appendBlock(block: Block): Either[ValidationError, Unit]


  def confirmations(block: Block): Option[Int] =
    heightOf(block).map(height() - _)

  /**
    *
    * @return Get list of blocks generated by specified address in specified interval of blocks
    */
  def generatedBy(account: Account, from: Int, to: Int): Seq[Block]

  /**
    * Block with maximum blockchain score
    */
  def lastBlock: Block = lastBlocks(1).head


  def lastBlockIds(howMany: Int): Seq[BlockId] = lastBlocks(howMany).map(b => b.uniqueId)

  /**
    * Return $howMany blocks starting from $parentSignature
    */

  /**
    * Average delay in milliseconds between last $blockNum blocks starting from $block
    */
  def averageDelay(block: Block, blockNum: Int): Try[Long] = Try {
    (block.timestampField.value - parent(block, blockNum).get.timestampField.value) / blockNum
  }

  def getCheckpoint: Option[Checkpoint]

  def setCheckpoint(checkpoint: Option[Checkpoint])

  def genesisBlock: Option[Block] = blockAt(1)

  def parent(block: Block, back: Int = 1): Option[Block] = {
    require(back > 0)
    heightOf(block.referenceField.value).flatMap(referenceHeight => blockAt(referenceHeight - back + 1))
  }

  def child(block: Block): Option[Block] = heightOf(block).flatMap(h => blockAt(h + 1))

  def discardBlock(): History

  def lastBlocks(howMany: Int): Seq[Block] =
    (Math.max(1, height() - howMany + 1) to height()).flatMap(blockAt).reverse

  def lookForward(parentSignature: BlockId, howMany: Int): Seq[BlockId] =
    heightOf(parentSignature).map { h =>
      (h + 1).to(Math.min(height(), h + howMany: Int)).flatMap(blockAt).map(_.uniqueId)
    }.getOrElse(Seq())

  def genesis: Block = blockAt(1).get
}

object History {
  type BlockchainScore = BigInt
}
