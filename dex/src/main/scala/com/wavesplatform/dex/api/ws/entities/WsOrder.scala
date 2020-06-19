package com.wavesplatform.dex.api.ws.entities

import cats.syntax.option._
import com.wavesplatform.dex.api.ws.doubleAsStringFormat
import com.wavesplatform.dex.domain.asset.Asset
import com.wavesplatform.dex.domain.asset.Asset.Waves
import com.wavesplatform.dex.domain.model.Denormalization
import com.wavesplatform.dex.domain.order.{Order, OrderType}
import com.wavesplatform.dex.error.ErrorFormatterContext
import com.wavesplatform.dex.json._
import com.wavesplatform.dex.model.{AcceptedOrder, AcceptedOrderType, OrderStatus}
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class WsOrder(id: Order.Id,
                   timestamp: Option[Long] = None,
                   amountAsset: Option[Asset] = None,
                   priceAsset: Option[Asset] = None,
                   side: Option[OrderType] = None,
                   isMarket: Option[Boolean] = None,
                   price: Option[Double] = None,
                   amount: Option[Double] = None,
                   fee: Option[Double] = None,
                   feeAsset: Option[Asset] = None,
                   status: Option[String] = None,
                   filledAmount: Option[Double] = None,
                   filledFee: Option[Double] = None,
                   avgWeighedPrice: Option[Double] = None)

object WsOrder {

  def fromDomain(ao: AcceptedOrder, status: OrderStatus)(implicit efc: ErrorFormatterContext): WsOrder = {

    val amountAssetDecimals = efc.assetDecimals(ao.order.assetPair.amountAsset)
    val priceAssetDecimals  = efc.assetDecimals(ao.order.assetPair.priceAsset)

    def denormalizeAmountAndFee(value: Long): Double = Denormalization.denormalizeAmountAndFee(value, amountAssetDecimals).toDouble
    def denormalizePrice(value: Long): Double        = Denormalization.denormalizePrice(value, amountAssetDecimals, priceAssetDecimals).toDouble

    WsOrder(
      ao.id,
      ao.order.timestamp.some,
      ao.order.assetPair.amountAsset.some,
      ao.order.assetPair.priceAsset.some,
      ao.order.orderType.some,
      ao.isMarket.some,
      ao.price.some.map(denormalizePrice),
      ao.order.amount.some.map(denormalizeAmountAndFee),
      ao.order.matcherFee.some.map(denormalizeAmountAndFee),
      ao.feeAsset.some,
      status.name.some,
      ao.fillingInfo.filledAmount.some.map(denormalizeAmountAndFee),
      ao.fillingInfo.filledFee.some.map(denormalizeAmountAndFee),
      ao.fillingInfo.avgWeighedPrice.some.map(denormalizePrice)
    )
  }

  def apply(id: Order.Id, status: String, filledAmount: Double, filledFee: Double, avgWeighedPrice: Double): WsOrder = {
    WsOrder(id, status = status.some, filledAmount = filledAmount.some, filledFee = filledFee.some, avgWeighedPrice = avgWeighedPrice.some)
  }

  def apply(id: Order.Id, status: String): WsOrder = WsOrder(id, status = status.some)

  val isMarketFormat: Format[Boolean] = AcceptedOrderType.acceptedOrderTypeFormat.coerce[Boolean](
    { case AcceptedOrderType.Market => true; case _ => false },
    if (_) AcceptedOrderType.Market else AcceptedOrderType.Limit
  )

  implicit class JsPathOps(private val path: JsPath) {
    val formatAsset: OFormat[Option[Asset]] = OFormat(
      Reads[Option[Asset]] { json =>
        path.asSingleJson(json) match {
          case JsDefined(JsNull) => JsSuccess(Waves.some)
          case JsDefined(value)  => Asset.assetFormat.reads(value).map(_.some)
          case JsUndefined()     => JsSuccess(None)
        }
      },
      Writes.nullable[Asset](path)(Asset.assetFormat.writes _)
    )
  }

  implicit val wsOrderFormat: Format[WsOrder] =
    (
      (__ \ "i").format[Order.Id] and                               // id
        (__ \ "t").formatNullable[Long] and                         // timestamp
        (__ \ "A").formatAsset and                                  // amount asset
        (__ \ "P").formatAsset and                                  // price asset
        (__ \ "S").formatNullable[OrderType] and                    // side: buy or sell
        (__ \ "T").formatNullable[Boolean](isMarketFormat) and      // type: market or limit
        (__ \ "p").formatNullable[Double](doubleAsStringFormat) and // price
        (__ \ "a").formatNullable[Double](doubleAsStringFormat) and // amount
        (__ \ "f").formatNullable[Double](doubleAsStringFormat) and // fee
        (__ \ "F").formatAsset and                                  // fee asset
        (__ \ "s").formatNullable[String] and                       // status: Accepted or Filled or PartiallyFilled or Cancelled
        (__ \ "q").formatNullable[Double](doubleAsStringFormat) and // filled amount
        (__ \ "Q").formatNullable[Double](doubleAsStringFormat) and // filled fee
        (__ \ "r").formatNullable[Double](doubleAsStringFormat)     // average weighed price among all trades
    )(WsOrder.apply, unlift(WsOrder.unapply))
}