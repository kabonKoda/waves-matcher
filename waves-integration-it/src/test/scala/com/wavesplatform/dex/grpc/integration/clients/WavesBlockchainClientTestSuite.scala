package com.wavesplatform.dex.grpc.integration.clients

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.wavesplatform.dex.collections.MapOps.Ops2D
import com.wavesplatform.dex.domain.account.{Address, KeyPair, PublicKey}
import com.wavesplatform.dex.domain.asset.Asset.{IssuedAsset, Waves}
import com.wavesplatform.dex.domain.asset.{Asset, AssetPair}
import com.wavesplatform.dex.domain.bytes.ByteStr
import com.wavesplatform.dex.domain.order.{Order, OrderType}
import com.wavesplatform.dex.domain.transaction.{ExchangeTransaction, ExchangeTransactionV2}
import com.wavesplatform.dex.domain.utils.EitherExt2
import com.wavesplatform.dex.grpc.integration.clients.combined.{CombinedStream, CombinedWavesBlockchainClient}
import com.wavesplatform.dex.grpc.integration.clients.domain.AddressBalanceUpdates
import com.wavesplatform.dex.grpc.integration.clients.domain.portfolio.SynchronizedPessimisticPortfolios
import com.wavesplatform.dex.grpc.integration.dto.BriefAssetDescription
import com.wavesplatform.dex.grpc.integration.settings.{GrpcClientSettings, WavesBlockchainClientSettings}
import com.wavesplatform.dex.grpc.integration.{IntegrationSuiteBase, WavesClientBuilder}
import com.wavesplatform.dex.it.test.{NoStackTraceCancelAfterFailure, Scripts}
import monix.execution.Scheduler

import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random

class WavesBlockchainClientTestSuite extends IntegrationSuiteBase with NoStackTraceCancelAfterFailure {

  private val grpcExecutor = Executors.newCachedThreadPool(
    new ThreadFactoryBuilder()
      .setDaemon(true)
      .setNameFormat("grpc-%d")
      .build()
  )

  implicit private val monixScheduler: Scheduler = monix.execution.Scheduler.cached("monix", 1, 5)

  private lazy val client =
    WavesClientBuilder.async(
      wavesBlockchainClientSettings = WavesBlockchainClientSettings(
        grpc = GrpcClientSettings(
          target = wavesNode1.matcherExtApiTarget,
          maxHedgedAttempts = 5,
          maxRetryAttempts = 5,
          keepAliveWithoutCalls = true,
          keepAliveTime = 2.seconds,
          keepAliveTimeout = 5.seconds,
          idleTimeout = 1.minute,
          channelOptions = GrpcClientSettings.ChannelOptionsSettings(connectTimeout = 5.seconds)
        ),
        blockchainUpdatesGrpc = GrpcClientSettings(
          target = wavesNode1.blockchainUpdatesExtApiTarget,
          maxHedgedAttempts = 5,
          maxRetryAttempts = 5,
          keepAliveWithoutCalls = true,
          keepAliveTime = 2.seconds,
          keepAliveTimeout = 5.seconds,
          idleTimeout = 1.minute,
          channelOptions = GrpcClientSettings.ChannelOptionsSettings(connectTimeout = 5.seconds)
        ),
        defaultCachesExpiration = 100.milliseconds,
        balanceStreamBufferSize = 100,
        combinedClientSettings = CombinedWavesBlockchainClient.Settings(
          maxRollbackHeight = 100,
          maxCachedLatestBlockUpdates = 5,
          combinedStream = CombinedStream.Settings(1.second),
          pessimisticPortfolios = SynchronizedPessimisticPortfolios.Settings(100)
        )
      ),
      matcherPublicKey = PublicKey(Array.emptyByteArray), // Doesn't matter here
      monixScheduler = monixScheduler,
      grpcExecutionContext = ExecutionContext.fromExecutor(grpcExecutor)
    )

  implicit override def patienceConfig: PatienceConfig = super.patienceConfig.copy(
    timeout = 1.minute,
    interval = 1.second
  )

  private val regularBalance = new AtomicReference(Map.empty[Address, Map[Asset, Long]])

  private val trueScript = Option(Scripts.alwaysTrue)

  private def assertRegularBalanceChanges(expectedBalanceChanges: Map[Address, Map[Asset, Long]]): Unit = eventually {
    // Remove pairs (address, asset) those expectedBalanceChanges has not
    val actual = simplify(
      regularBalance.get.view
        .filterKeys(expectedBalanceChanges.keys.toSet)
        .map {
          case (address, balance) => address -> balance.view.filterKeys(expectedBalanceChanges(address).contains).toMap
        }
        .toMap
    )
    val expected = simplify(expectedBalanceChanges)
    withClue(s"actual=$actual vs expected=$expected\n") {
      actual should matchTo(expected)
    }
  }

  private def simplify(xs: Map[Address, Map[Asset, Long]]): String =
    xs.toList
      .map {
        case (address, assets) =>
          val xs = assets
            .map { case (asset, v) => asset.toString -> v }
            .toList
            .sortBy(_._1)
            .map { case (asset, v) => s"$v $asset" }
            .mkString(", ")
          address.stringRepr -> xs
      }
      .sortBy(_._1)
      .map {
        case (address, assets) => s"$address: ($assets)"
      }
      .mkString("; ")

  override def beforeAll(): Unit = {
    super.beforeAll()
    broadcastAndAwait(IssueUsdTx, IssueBtcTx)
    client.updates.foreach { update =>
      log.info(s"Got in test: $update")
      regularBalance.updateAndGet { orig =>
        update.balanceUpdates.foldLeft(orig) { case (r, (address, xs)) =>
          r.deepReplace(Map(address -> xs.regular))
        }
      }
    }
  }

  "DEX client should receive balance changes via gRPC" in {
    val aliceInitialBalance = wavesNode1.api.balance(alice, Waves)

    val issueAssetTx = mkIssue(alice, "name", someAssetAmount, 2)
    val issuedAsset = IssuedAsset(issueAssetTx.id())

    regularBalance.set(Map.empty)
    broadcastAndAwait(issueAssetTx)

    assertRegularBalanceChanges {
      Map(
        alice.toAddress -> Map(
          Waves -> (aliceInitialBalance - issueFee),
          issuedAsset -> someAssetAmount
        )
      )
    }

    regularBalance.set(Map.empty)
    broadcastAndAwait(mkTransfer(alice, bob, someAssetAmount, issuedAsset))

    assertRegularBalanceChanges {
      Map(
        alice.toAddress -> Map(
          Waves -> (aliceInitialBalance - issueFee - minFee),
          issuedAsset -> 0L
        ),
        bob.toAddress -> Map(
          issuedAsset -> someAssetAmount
        )
      )
    }
  }

  "areKnown" - {
    "false for unknown tx" in {
      wait(client.areKnown(Seq(EthId))).values.head shouldBe false
    }

    "true for confirmed tx" in {
      broadcastAndAwait(IssueEthTx)
      eventually {
        wait(client.areKnown(Seq(EthId))).values.head shouldBe true
      }
    }
  }

  "broadcastTx" - {
    "returns true" - {
      val pair: AssetPair = AssetPair.createAssetPair(UsdId.toString, "WAVES").get // TODO
      def mkExchangeTx: ExchangeTransaction = mkDomainExchange(bob, alice, pair, 1L, 2 * Order.PriceConstant, matcher = matcher)

      "if the transaction passed the validation and was added to the UTX pool" in {
        val exchangeTx = mkExchangeTx

        withClue(exchangeTx.id().base58) {
          wait(client.broadcastTx(exchangeTx)) shouldBe BroadcastResult.Added
          wavesNode1.api.waitForTransaction(exchangeTx.id())
        }
      }

      "if the transaction is in the blockchain" in {
        // Otherwise there is a high probability, that exchangeTx will be moved to a priority pool during an appending of a key block
        //  thus it will be added without expected issues.
        wavesNode1.api.waitForHeightArise()

        val exchangeTx = mkExchangeTx
        withClue(exchangeTx.id().base58) {
          broadcastAndAwait(exchangeTx.toWavesJ())
          wait(client.broadcastTx(exchangeTx)) shouldBe a[BroadcastResult.Failed]
        }
      }
    }

    "returns false if the transaction didn't pass the validation" in {
      val now = System.currentTimeMillis()

      val executedAmount = 1L
      val executedPrice = 2 * Order.PriceConstant
      val pair = AssetPair.createAssetPair(UsdId.toString, "WAVES").get
      val fakeBob = KeyPair("fake-bob".getBytes(StandardCharsets.UTF_8))

      val buy = mkOrder(alice, pair, OrderType.BUY, executedAmount, executedPrice, matcher = matcher)
      val sell = mkOrder(fakeBob, pair, OrderType.SELL, executedAmount, executedPrice, matcher = matcher)

      val exchangeTx =
        ExchangeTransactionV2
          .create(
            matcher = matcher,
            buyOrder = buy,
            sellOrder = sell,
            amount = executedAmount,
            price = executedPrice,
            buyMatcherFee = matcherFee,
            sellMatcherFee = matcherFee,
            fee = matcherFee,
            timestamp = now
          )
          .explicitGet()

      wait(client.broadcastTx(exchangeTx)) shouldBe a[BroadcastResult.Failed]
    }
  }

  "isFeatureActivated" - {
    "returns false for not yet activated feature" in {
      wait(client.isFeatureActivated(1)) shouldBe false // node's BlockchainFeatures.SmallerMinimalGeneratingBalance.id
    }

    "returns true for activated feature" in {
      wait(client.isFeatureActivated(2)) shouldBe true // node's BlockchainFeatures.NG.id
    }
  }

  "assetDescription" - {
    "returns None if there is no such asset" in {
      wait(client.assetDescription(wct)) shouldBe None
    }

    "returns an information for created assets" in {
      wait(client.assetDescription(usd)) should matchTo(
        Option(
          BriefAssetDescription(
            name = IssueUsdTx.name(),
            decimals = IssueUsdTx.decimals(),
            hasScript = false
          )
        )
      )
    }
  }

  "hasScript/runScript(IssuedAsset)" - {
    "hasn't a script" in {
      wait(client.hasScript(usd)) shouldBe false
    }

    "has a script" in {
      val issueTx = mkIssue(bob, "SmartCoin", defaultAssetQuantity, 8, smartIssueFee, trueScript)

      withClue("issue scripted asset") {
        broadcastAndAwait(issueTx)

        eventually {
          wait(client.hasScript(IssuedAsset(issueTx.id()))) shouldBe true
        }
      }

      withClue("run script") {
        val pair = AssetPair.createAssetPair(issueTx.id().toString, "WAVES").get
        val exchangeTx = mkDomainExchange(bob, alice, pair, 1L, 2 * Order.PriceConstant, matcherFee = 1.waves, matcher = matcher)

        wait(client.runScript(IssuedAsset(issueTx.id()), exchangeTx)) shouldBe RunScriptResult.Allowed
      }
    }
  }

  "hasScript/runScript(Address)" - {
    "returns false if there is no script" in {
      wait(client.hasScript(matcher)) shouldBe false
    }

    "returns true if a script was assigned" in {
      val receiver = KeyPair("receiver".getBytes(StandardCharsets.UTF_8))

      withClue("transfer") {
        broadcastAndAwait(mkTransfer(alice, receiver, 5.waves, Waves))
      }

      withClue("set script") {
        val setScriptTx = mkSetAccountMayBeScript(receiver, trueScript)
        broadcastAndAwait(setScriptTx)

        eventually {
          wait(client.hasScript(receiver)) shouldBe true
        }
      }

      withClue("run script") {
        val now = System.currentTimeMillis()
        val pair = AssetPair.createAssetPair(UsdId.toString, "WAVES").get
        val buy = Order.buy(bob, matcher, pair, 1L, 2 * Order.PriceConstant, now, now + 1.day.toMillis, 0)

        wait(client.runScript(receiver, buy)) shouldBe RunScriptResult.Allowed
      }
    }
  }

  "partialBalancesSnapshot" in {
    val leaseAmount = 1.waves
    val leaseTx = mkLease(bob, alice, leaseAmount)
    val balanceBefore = wavesNode1.api.balance(bob, Waves)
    wavesNode1.api.broadcast(leaseTx)

    wait(client.partialBalancesSnapshot(bob, Set(Waves, randomIssuedAsset))) should {
      // leaseTx haven't been added to UTX yet
      matchTo(AddressBalanceUpdates(
        regular = Map(Waves -> balanceBefore),
        outgoingLeasing = Some(0L),
        pessimisticCorrection = Map.empty
      )) or
      // not confirmed
      matchTo(AddressBalanceUpdates(
        regular = Map(Waves -> balanceBefore),
        outgoingLeasing = Some(0L),
        pessimisticCorrection = Map(Waves -> -(leaseAmount + leasingFee))
      )) or
      // confirmed
      matchTo(AddressBalanceUpdates(
        regular = Map(Waves -> (balanceBefore - leaseAmount - leasingFee)),
        outgoingLeasing = Some(leaseAmount),
        pessimisticCorrection = Map.empty
      ))
    }

    wavesNode1.api.waitForTransaction(leaseTx)
    broadcastAndAwait(mkLeaseCancel(bob, leaseTx.id()))
  }

  "fullBalancesSnapshot" in {
    val carol = mkKeyPair("carol")

    broadcastAndAwait(
      mkTransfer(bob, carol, 10.waves, Waves),
      mkTransfer(alice, carol, 1.usd, usd),
      mkTransfer(bob, carol, 1.btc, btc)
    )

    val leaseAmount = 1.waves
    val leaseTx = mkLease(carol, bob, leaseAmount)
    wavesNode1.api.broadcast(leaseTx)

    wait(client.fullBalancesSnapshot(carol, Set(btc))) should {
      // leaseTx haven't been added to UTX yet
      matchTo(AddressBalanceUpdates(
        regular = Map[Asset, Long](
          Waves -> 10.waves,
          usd -> 1.usd
        ),
        outgoingLeasing = Some(0L),
        pessimisticCorrection = Map.empty
      )) or
      // not confirmed
      matchTo(AddressBalanceUpdates(
        regular = Map[Asset, Long](
          Waves -> 10.waves,
          usd -> 1.usd
        ),
        outgoingLeasing = Some(0L),
        pessimisticCorrection = Map(Waves -> -(leaseAmount + leasingFee))
      )) or
      // confirmed
      matchTo(AddressBalanceUpdates(
        regular = Map[Asset, Long](
          Waves -> (10.waves - leaseAmount - leasingFee),
          usd -> 1.usd
        ),
        outgoingLeasing = Some(leaseAmount),
        pessimisticCorrection = Map.empty
      ))
    }
  }

  "isOrderConfirmed" - {
    "no such order" in {
      wait(client.isOrderConfirmed(randomByteStr(32))) shouldBe false
    }

    "the order was in a forged ExchangeTransaction" in {
      val pair = AssetPair.createAssetPair(UsdId.toString, "WAVES").get
      val exchangeTx = mkExchange(bob, alice, pair, 1L, 2 * Order.PriceConstant, matcher = matcher)

      withClue(exchangeTx.id().base58) {
        broadcastAndAwait(exchangeTx)
        eventually {
          wait(client.isOrderConfirmed(exchangeTx.buyOrder().id())) shouldBe true
          wait(client.isOrderConfirmed(exchangeTx.sellOrder().id())) shouldBe true
        }
      }
    }
  }

  "is able to work after a rollback" in {
    val height = wavesNode1.api.currentHeight
    wavesNode1.api.waitForHeight(height + 2)

    val aliceBalanceBefore = wavesNode1.api.balance(alice, Waves)
    wavesNode1.api.rollback(height, returnTransactionsToUtx = false)

    wavesNode1.api.broadcast(mkTransfer(alice, bob, 1.waves, Waves))
    eventually {
      wavesNode1.api.balance(alice, Waves) shouldBe (aliceBalanceBefore - 1.waves - minFee)
    }
  }

  // TODO check that the functions returns new data after the state is changed?

  override protected def afterAll(): Unit = {
    Await.ready(client.close(), 10.seconds)
    super.afterAll()
    grpcExecutor.shutdownNow()
  }

  private def wait[T](f: => Future[T]): T = Await.result(f, 10.seconds)

  private def randomByteStr(len: Int): ByteStr = {
    val inner = new Array[Byte](len)
    Random.nextBytes(inner)
    ByteStr(inner)
  }

  private def randomIssuedAsset: IssuedAsset = IssuedAsset(randomByteStr(32))
}