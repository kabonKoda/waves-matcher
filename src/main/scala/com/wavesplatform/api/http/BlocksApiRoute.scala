package com.wavesplatform.api.http

import akka.http.scaladsl.server.{Route, StandardRoute}
import com.wavesplatform.block.BlockHeader
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.settings.RestAPISettings
import com.wavesplatform.state.Blockchain
import com.wavesplatform.transaction._
import io.netty.channel.group.ChannelGroup
import io.swagger.annotations._
import javax.ws.rs.Path
import play.api.libs.json._

@Path("/blocks")
@Api(value = "/blocks")
case class BlocksApiRoute(settings: RestAPISettings, blockchain: Blockchain, allChannels: ChannelGroup) extends ApiRoute {

  // todo: make this configurable and fix integration tests
  val MaxBlocksPerRequest = 100

  override lazy val route =
    pathPrefix("blocks") {
      signature ~ first ~ last ~ lastHeaderOnly ~ at ~ atHeaderOnly ~ seq ~ seqHeaderOnly ~ height ~ heightEncoded ~ child ~ address ~ delay
    }

  @Path("/address/{address}/{from}/{to}")
  @ApiOperation(value = "Blocks produced by address", notes = "Get list of blocks generated by specified address", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "from", value = "Start block height", required = true, dataType = "integer", paramType = "path"),
      new ApiImplicitParam(name = "to", value = "End block height", required = true, dataType = "integer", paramType = "path"),
      new ApiImplicitParam(name = "address", value = "Address", required = true, dataType = "string", paramType = "path")
    ))
  def address: Route = (path("address" / Segment / IntNumber / IntNumber) & get) {
    case (address, start, end) =>
      if (end >= 0 && start >= 0 && end - start >= 0 && end - start < MaxBlocksPerRequest) {
        val blocks = JsArray(
          (start to end)
            .map { height =>
              (blockchain.blockAt(height), height)
            }
            .filter(_._1.isDefined)
            .map { pair =>
              (pair._1.get, pair._2)
            }
            .filter(_._1.signerData.generator.address == address)
            .map { pair =>
              pair._1.json() + ("height" -> Json.toJson(pair._2))
            })
        complete(blocks)
      } else complete(TooBigArrayAllocation)
  }

  @Path("/child/{signature}")
  @ApiOperation(value = "Child block", notes = "Get successor of specified block", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "signature", value = "Base58-encoded block signature", required = true, dataType = "string", paramType = "path")
    ))
  def child: Route = (path("child" / Segment) & get) { encodedSignature =>
    withBlock(blockchain, encodedSignature) { block =>
      val childJson = for {
        h <- blockchain.heightOf(block.uniqueId)
        b <- blockchain.blockAt(h + 1)
      } yield b.json()

      complete(childJson.getOrElse[JsObject](Json.obj("status" -> "error", "details" -> "No child blocks")))
    }
  }

  @Path("/delay/{signature}/{blockNum}")
  @ApiOperation(
    value = "Average block delay",
    notes = "Average delay in milliseconds between last `blockNum` blocks starting from block with `signature`",
    httpMethod = "GET"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "signature", value = "Base58-encoded block signature", required = true, dataType = "string", paramType = "path"),
      new ApiImplicitParam(name = "blockNum", value = "Number of blocks to count delay", required = true, dataType = "string", paramType = "path")
    ))
  def delay: Route = (path("delay" / Segment / IntNumber) & get) { (encodedSignature, count) =>
    withBlock(blockchain, encodedSignature) { block =>
      if (count <= 0) complete(CustomValidationError("Block count should be positive"))
      else
        blockchain
          .parent(block, count)
          .map(parent => complete(Json.obj("delay" -> (block.timestamp - parent.timestamp) / count)))
          .getOrElse(complete(CustomValidationError(s"Cannot go $count blocks back")))
    }
  }

  @Path("/height/{signature}")
  @ApiOperation(value = "Block height", notes = "Height of a block by its signature", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "signature", value = "Base58-encoded block signature", required = true, dataType = "string", paramType = "path")
    ))
  def heightEncoded: Route = (path("height" / Segment) & get) { encodedSignature =>
    if (encodedSignature.length > TransactionParsers.SignatureStringLength)
      complete(InvalidSignature)
    else {
      ByteStr
        .decodeBase58(encodedSignature)
        .toOption
        .toRight(InvalidSignature)
        .flatMap(s => blockchain.heightOf(s).toRight(BlockDoesNotExist)) match {
        case Right(h) => complete(Json.obj("height" -> h))
        case Left(e)  => complete(e)
      }
    }
  }

  @Path("/height")
  @ApiOperation(value = "Blockchain height", notes = "Get current blockchain height", httpMethod = "GET")
  def height: Route = (path("height") & get) {
    complete(Json.obj("height" -> blockchain.height))
  }

  @Path("/at/{height}")
  @ApiOperation(value = "Block at height", notes = "Get block at specified height", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "height", value = "Block height", required = true, dataType = "integer", paramType = "path")
    ))
  def at: Route = (path("at" / IntNumber) & get)(at(_, includeTransactions = true))

  @Path("/headers/at/{height}")
  @ApiOperation(value = "Block header at height", notes = "Get block header at specified height", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "height", value = "Block height", required = true, dataType = "integer", paramType = "path")
    ))
  def atHeaderOnly: Route = (path("headers" / "at" / IntNumber) & get)(at(_, includeTransactions = false))

  private def at(height: Int, includeTransactions: Boolean): StandardRoute = {
    (if (includeTransactions) {
       blockchain.blockAt(height).map(_.json())
     } else {
       blockchain.blockHeaderAndSize(height).map { case (bh, s) => BlockHeader.json(bh, s) }
     }) match {
      case Some(json) => complete(json + ("height" -> JsNumber(height)))
      case None       => complete(Json.obj("status" -> "error", "details" -> "No block for this height"))
    }
  }

  @Path("/seq/{from}/{to}")
  @ApiOperation(value = "Block range", notes = "Get blocks at specified heights", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "from", value = "Start block height", required = true, dataType = "integer", paramType = "path"),
      new ApiImplicitParam(name = "to", value = "End block height", required = true, dataType = "integer", paramType = "path")
    ))
  def seq: Route = (path("seq" / IntNumber / IntNumber) & get) { (start, end) =>
    seq(start, end, includeTransactions = true)
  }

  @Path("/headers/seq/{from}/{to}")
  @ApiOperation(value = "Block header range", notes = "Get block headers at specified heights", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "from", value = "Start block height", required = true, dataType = "integer", paramType = "path"),
      new ApiImplicitParam(name = "to", value = "End block height", required = true, dataType = "integer", paramType = "path")
    ))
  def seqHeaderOnly: Route = (path("headers" / "seq" / IntNumber / IntNumber) & get) { (start, end) =>
    seq(start, end, includeTransactions = false)
  }

  private def seq(start: Int, end: Int, includeTransactions: Boolean): StandardRoute = {
    if (end >= 0 && start >= 0 && end - start >= 0 && end - start < MaxBlocksPerRequest) {
      val blocks = JsArray((start to end).flatMap { height =>
        (if (includeTransactions) {
           blockchain.blockAt(height).map(_.json())
         } else {
           blockchain.blockHeaderAndSize(height).map { case (bh, s) => BlockHeader.json(bh, s) }
         }).map(_ + ("height" -> Json.toJson(height)))
      })
      complete(blocks)
    } else complete(TooBigArrayAllocation)
  }

  @Path("/last")
  @ApiOperation(value = "Last block", notes = "Get last block", httpMethod = "GET")
  def last: Route = (path("last") & get)(last(includeTransactions = true))

  @Path("/headers/last")
  @ApiOperation(value = "Last block header", notes = "Get last block header", httpMethod = "GET")
  def lastHeaderOnly: Route = (path("headers" / "last") & get)(last(includeTransactions = false))

  def last(includeTransactions: Boolean): StandardRoute = {
    complete {
      {
        val height = blockchain.height

        (if (includeTransactions) {
           blockchain.blockAt(height).get.json()
         } else {
           val bhs = blockchain.blockHeaderAndSize(height).get
           BlockHeader.json(bhs._1, bhs._2)
         }) + ("height" -> Json.toJson(height))
      }
    }
  }

  @Path("/first")
  @ApiOperation(value = "Genesis block", notes = "Get genesis block", httpMethod = "GET")
  def first: Route = (path("first") & get) {
    complete(blockchain.genesis.json() + ("height" -> Json.toJson(1)))
  }

  @Path("/signature/{signature}")
  @ApiOperation(value = "Block by signature", notes = "Get block by its signature", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "signature", value = "Base58-encoded block signature", required = true, dataType = "string", paramType = "path")
    ))
  def signature: Route = (path("signature" / Segment) & get) { encodedSignature =>
    if (encodedSignature.length > TransactionParsers.SignatureStringLength) complete(InvalidSignature)
    else {
      ByteStr
        .decodeBase58(encodedSignature)
        .toOption
        .toRight(InvalidSignature)
        .flatMap(s => blockchain.blockById(s).toRight(BlockDoesNotExist)) match {
        case Right(block) => complete(block.json() + ("height" -> blockchain.heightOf(block.uniqueId).map(Json.toJson(_)).getOrElse(JsNull)))
        case Left(e)      => complete(e)
      }
    }
  }
}
