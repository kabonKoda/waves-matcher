package com.wavesplatform.dex.smart

import java.nio.charset.StandardCharsets

import com.wavesplatform.account.KeyPair
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.dex.WavesExtSuiteBase
import com.wavesplatform.dex.grpc.integration.smart.MatcherScriptRunner
import com.wavesplatform.dex.test.matchers.ProduceError.produce
import com.wavesplatform.lang.script.Script
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.lang.v1.estimator.v2.ScriptEstimatorV2
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order, OrderType}
import com.wavesplatform.transaction.smart.script.ScriptCompiler

class MatcherScriptRunnerSpecification extends WavesExtSuiteBase {

  private val sampleOrder = Order.selfSigned(
    version = 1.toByte,
    sender = KeyPair(ByteStr("test".getBytes(StandardCharsets.UTF_8))),
    matcher = KeyPair(ByteStr("matcher".getBytes(StandardCharsets.UTF_8))).publicKey,
    assetPair = AssetPair(Waves, IssuedAsset(ByteStr("asset".getBytes("utf-8")))),
    orderType = OrderType.BUY,
    price = 100000000L,
    amount = 100L,
    timestamp = 1L,
    expiration = 1000L,
    matcherFee = 30000L
  )

  private def run(script: Script): Either[String, Terms.EVALUATED] = MatcherScriptRunner(script, sampleOrder, false)

  "dApp sunny day" in {
    run(dAppScriptSunny).explicitGet() shouldBe Terms.FALSE
  }

  "Blockchain functions are disabled in dApp" in {
    run(dAppScriptBlockchain) should produce("An access to <getBoolean(addressOrAlias: Address|Alias, key: String): Boolean|Unit> is denied")
  }

  private def dAppScriptSunny: Script =
    ScriptCompiler
      .compile(
        s"""|{-# STDLIB_VERSION 3 #-}
            |{-# CONTENT_TYPE DAPP #-}
            |{-# SCRIPT_TYPE ACCOUNT #-}
            |
            |let addr = addressFromPublicKey(base58'H1kGVNdJwV7N5dF73YWs1R8uet6g5bCvTHmTnYy1hSnr')
            |
            |@Verifier(x)
            |func verifier() = {
            |    match(x) {
            |      case o: Order => o.sender == addr
            |      case _ => false
            |    }
            |}
            |""".stripMargin,
        ScriptEstimatorV2
      )
      .explicitGet()
      ._1

  private def dAppScriptBlockchain: Script =
    ScriptCompiler
      .compile(
        s"""|{-# STDLIB_VERSION 3 #-}
            |{-# CONTENT_TYPE DAPP #-}
            |{-# SCRIPT_TYPE ACCOUNT #-}
            |
            |@Verifier(x)
            |func verifier() = {
            |    match(x) {
            |      case o: Order => getBooleanValue(o.sender, "foo")
            |      case _ => false
            |    }
            |}
            |""".stripMargin,
        ScriptEstimatorV2
      )
      .explicitGet()
      ._1

}
