package org.nlogo.auction

import org.nlogo.agent.World
import org.nlogo.api.ExtensionException
import org.nlogo.{agent, api, core}
import core.Syntax._
import api.ScalaConversions._  // implicits
import scala.collection.mutable.HashMap
import scala.util.Random

class AuctionExtension extends api.DefaultClassManager {

  def load(manager: api.PrimitiveManager) {
    // manager.addPrimitive("markets", ReportMarkets)
    manager.addPrimitive("setup-market", SetupMarket)
    manager.addPrimitive("buy", Buyer)
    manager.addPrimitive("sell", Seller)
    manager.addPrimitive("clear", Clear)
    manager.addPrimitive("last-trade-price", LastTradePriceReporter)
    manager.addPrimitive("last-trade-volume", LastTradeVolumeReporter)
  }
}

class Market(asset: String, currency: String, world: World) {
  /* Market is connected to an agent. It has methods for handling orders
   * we use a HashMap so we can do incremental updates with repeating prices
   * we should only have a single price key with size to find the fill price
   * World gives direct access to turtles by who numbers
   */

  // for setTraderVariable we want index numbers to reuse for asset and currency
  var indexCache = new HashMap[String, Int]()

  // initializes empty HashMaps for bids/asks
  var bidHash = new HashMap[Int, Int]()
  var askHash = new HashMap[Int, Int]()
  // clear all orders with empty list
  var bidOrders = List[(Int, Int, Int)]()
  var askOrders = List[(Int, Int, Int)]()
  // with each clear, calculate a volume and take the price for last trade
  var lastTradePrice = 0
  var lastTradeVolume = 0
  // this method is for transactions with turtles

  def getVariableIndex(turtle: api.Turtle, varName: String): Int = {
    /* gets the index of the variable by searching all the variables by name
     */
    val upperName = varName.toUpperCase
    val turt = turtle.asInstanceOf[agent.Turtle]
    (0 until turtle.variables.length).find(i => upperName == turt.variableName(i)).getOrElse(-1)
  }

  def setTraderVariable(turtle: api.Turtle, varName: String, value: Double): Unit = {
    /* Sets the turtles variables by names
     * finds the index to handle the string, Caches it
     */
    var varIndex = indexCache.getOrElse(varName, -1)
    if (varIndex == -1)
      varIndex = getVariableIndex(turtle, varName)
      indexCache(varName) = varIndex
    if (varIndex >= 0) {
      turtle.setVariable(varIndex, value.asInstanceOf[java.lang.Double])
    } else {
      throw new api.ExtensionException("variable not present for turtle to set")
    }
  }

  def getTraderVariable(turtle: api.Turtle, varName: String): Double = {
    /* Gets the turtles variables by names, using getVariable taking the string
     */
    var varIndex = indexCache.getOrElse(varName, -1)
    if (varIndex == -1)
      varIndex = getVariableIndex(turtle, varName)
      indexCache(varName) = varIndex
    turtle.getVariable(varIndex) match {
      case x: java.lang.Double => x.doubleValue
      case _ => throw new ExtensionException("variable not a number")
    }
  }

  // next two methods add orders

  def addAsk(price: Int, quantity: Int, traderId: Int): Unit = {
    /* called from Seller
     * this procedure adds the size to the price in a bid Hashmap
     * it then adds the order to an orderbook for filling the traders by Id
     */
    bidHash(price) = bidHash.getOrElse(price, 0) + quantity
    bidOrders = (price, quantity, traderId) :: bidOrders
  }

  def addBid(price: Int, quantity: Int, traderId: Int): Unit = {
    /* called from Buyer
     * this procedure adds the size to the price in an ask Hashmap
     * it then adds the order to an orderbook for filling the traders by Id
     */
    askHash(price) = askHash.getOrElse(price, 0) + quantity
    askOrders = (price, quantity, traderId) :: askOrders
  }

  // checks the order before passing it.

  def checkSellOrder(trader: api.Turtle, quantity: Int): Boolean = {
    // if quantity passes then place sell order
    quantity <= getTraderVariable(trader, asset)
  }

  def checkBuyOrder(trader: api.Turtle, cost: Int): Boolean = {
    // if quantity passes then place buy order
    cost <= getTraderVariable(trader, currency)
  }

  // next two methods hold assets or currency

  def holdTradersAsset(trader: api.Turtle, quantity: Int): Unit = {
    // removes traders asset until execution is complete
    val holdings = getTraderVariable(trader, asset).intValue
    setTraderVariable(trader, asset,  holdings - quantity)
  }

  def holdTradersCurrency(trader: api.Turtle, cost: Double): Unit = {
    // removes traders currency until execution is complete
    val money = getTraderVariable(trader, currency)
    setTraderVariable(trader, currency, money - cost)
  }

  // next two methods sort price, size for finding the trade price

  def sortBids(): List[(Int, Int)] = {
    /* turn our hashmap into a list of tuples for finding price
     * sorts highest bidding price first, which is the first to fill
     */
    bidHash.iterator.toList.sortBy(- _._1)
  }

  def sortAsks(): List[(Int, Int)] = {
    /* turn our hashmap into a list of tuples for finding price
     * sorts lowest asking price first, which is the first to fill
     */
    askHash.iterator.toList.sortBy(_._1)
  }

  // next six methods used in filling orders

  def fillAllTraders(pss: Tuple3[Int, Int, String]): Unit = {
    /* if pss does not exist, that means there is no trades
     * pss includes the price that all traders will be filled at
     * the size and side (ss) are only used for filling orders at the trade price
     * get the world and use traders' who numbers to fill them on orders
     */
    val price = pss._1
    val size = pss._2
    val side = pss._3

    // this is the trade price during the clearing event
    lastTradePrice = price
    // with each clear, calculate a volume
    lastTradeVolume = 0

    // if there is a partial fill (most often), we will fill asymmetrically
    if (side != "none")
      fillPartial(price, size, side)

    if (side == "bid") {
      // the bid is partially filled at the trade price
      val fillBids = bidOrders.filter(_._1 > price)
      val fillAsks = askOrders.filter(_._1 <= price)
      // here, we fill all the normal orders
      fillBids.map(order => fillOrderBid(order, price))
      fillAsks.map(order => fillOrderAsk(order, price))

      // lastTradeVolume has been updated with partial fills.
      lastTradeVolume = lastTradeVolume + fillBids.map(_._2).sum + fillAsks.map(_._2).sum
    }

    else if (side == "ask") {
      // the ask is partially filled at the trade price
      val fillBids = bidOrders.filter(_._1 >= price)
      val fillAsks = askOrders.filter(_._1 < price)
      // here, we fill all the normal orders
      fillBids.map(order => fillOrderBid(order, price))
      fillAsks.map(order => fillOrderAsk(order, price))

      // lastTradeVolume has been updated with partial fills.
      lastTradeVolume = lastTradeVolume + fillBids.map(_._2).sum + fillAsks.map(_._2).sum
    }

    else {
      // all orders are entirely filled at the trade price
      val fillBids = bidOrders.filter(_._1 >= price)
      val fillAsks = askOrders.filter(_._1 <= price)
      // here, we fill all the normal orders
      fillBids.map(order => fillOrderBid(order, price))
      fillAsks.map(order => fillOrderAsk(order, price))

      // lastTradeVolume has been updated with partial fills.
      lastTradeVolume = lastTradeVolume + fillBids.map(_._2).sum + fillAsks.map(_._2).sum
    }

  }

  def fillPartial(price: Int, size: Int, side: String): Unit = {
    /* the final fill price with pss should fill a partial on each order
     * at that price with this side.
     * these orders are filled pro-rata as partial fills
     */
    if (side == "bid") {
      val partFillBids = bidOrders.filter(_._1 == price)
      // fill a percent of each order, last gets the remainder
      val totalToFill = bidHash(price) - size
      val percentToFill = totalToFill.toFloat / bidHash(price)
      // only get the sizes of the orders
      val partFillBidSizes = partFillBids.map(_._2)
      // get a list of pro-rata sizes
      val proportionateFills = partFillBidSizes.map(size =>
                                        math.round(size * percentToFill).toInt)
      // there may be rounding errors, so sum the list and get difference
      val sizeGap = size - proportionateFills.sum
      // the biggest order will fill the gap of size fills vs pro-rata
      val gapFillIdx = proportionateFills.indexOf(proportionateFills.max)
      val finalFills = proportionateFills.updated(gapFillIdx,
                                      proportionateFills(gapFillIdx) + sizeGap)
      val bidOrdersFills = (partFillBids zip finalFills)
      bidOrdersFills.map{ case (o, f) => fillOrderPartialBid(o, f) }
      // lastTradeVolume should be zero before this
      lastTradeVolume = totalToFill

      }
    if (side == "ask") {
      val partFillAsks = askOrders.filter(_._1 == price)
      // fill a percent of each order, last gets the remainder
      val totalToFill = askHash(price) - size
      val percentToFill = totalToFill.toFloat / askHash(price)
      // only get the sizes of the orders
      val partFillAskSizes = partFillAsks.map(_._2)
      // get a list of pro-rata sizes
      val proportionateFills = partFillAskSizes.map(size =>
                                        math.round(size * percentToFill).toInt)
       // there may errors caused by rounding, so sum the list and get difference
      val sizeGap = size - proportionateFills.sum
      // the biggest order will fill the gap of size fills vs pro-rata
      val gapFillIdx = proportionateFills.indexOf(proportionateFills.max)
      val finalFills = proportionateFills.updated(gapFillIdx,
                                      proportionateFills(gapFillIdx) + sizeGap)
      val askOrdersFills = (partFillAsks zip finalFills)
      askOrdersFills.map{ case (o, f) => fillOrderPartialAsk(o, f) }
      // lastTradeVolume should be zero before this
      lastTradeVolume = totalToFill
     }
  }

  // orders are price, size, who number - use who number to find turtle to fill

  def fillOrderBid(order: Tuple3[Int, Int, Int], tradePrice: Int): Unit = {
    /* fills a single buy order that has been placed
     * maps to all buy orders above trade price and, if side == sell at price
     * delivers assets to traders and returns difference of money
     */
     // gets the trader associated with the id of the order
    val trader = world.getTurtle(order._3)
    val bidPrice = order._1
    val quantity = order._2

    val money = getTraderVariable(trader, currency)
    val holdings = getTraderVariable(trader, asset)

    // assets to add are equal to the quantity traded
    setTraderVariable(trader, asset, holdings + quantity)

    // money is returned by quantity times difference between bidPrice and tradePrice
    val moneyRet = (bidPrice - tradePrice) * quantity
    setTraderVariable(trader, currency, money + moneyRet)

   }

  def fillOrderAsk(order: Tuple3[Int, Int, Int], tradePrice: Int): Unit = {
    /* fills a single sell order that has been placed
     * maps to all sell orders below trade price and, if side == buy at price
     * delivers money to traders
     */
    val trader = world.getTurtle(order._3)
    val quantity = order._2

    val money = getTraderVariable(trader, currency)
    val moneyMade = tradePrice * quantity

    // the sellers get more money than they hoped, but trade their quantity asset
    // therefore, there is no change in sellers assets which have been held
    setTraderVariable(trader, currency, money + moneyMade)
  }

  def fillOrderPartialBid(order: Tuple3[Int, Int, Int], fillSize: Int): Unit = {
    /* when partial orders get filled, we must return money and add
     * to assets of the trader who is buying the fillSize
     * trade price is equal to order price in this case
     */
    val trader = world.getTurtle(order._3)
    val tradePrice = order._1
    val quantity = order._2

    val money = getTraderVariable(trader, currency)
    val holdings = getTraderVariable(trader, asset)

    // money is returned for unfilled quantity times price
    val moneyRet = tradePrice * (quantity - fillSize)
    setTraderVariable(trader, currency, money + moneyRet)

    // assets to add are equal to the fillSize
    setTraderVariable(trader, asset, holdings + fillSize)
  }

  def fillOrderPartialAsk(order: Tuple3[Int, Int, Int], fillSize: Int): Unit = {
    /* when partial orders get filled, we must return asset and add
     * money to the trader who is selling the fillSize
     * trade price is equal to order price in this case
     */
    val trader = world.getTurtle(order._3)
    val tradePrice = order._1
    val quantity = order._2

    val money = getTraderVariable(trader, currency)
    val holdings = getTraderVariable(trader, asset)

    // money is paid on filled quantity
    val moneyMade = tradePrice * fillSize
    setTraderVariable(trader, currency, money + moneyMade)

    // assets returned are equal to the unfilled quantity
    val assetRet = quantity - fillSize
    setTraderVariable(trader, asset, holdings + assetRet)
  }

  // next three methods clear any orders that have been left unfilled

  def returnAllHoldings(pss: Tuple3[Int, Int, String]): Unit = {
    /* returns all holdings above the price level for asks and
     * all the holdings below the price level for bids
     */
    if (pss._3 == "no-trade") {
      val returnBidOrdersMoney = bidOrders
      val returnAskOrdersAsset = askOrders

      returnBidOrdersMoney.map(order => returnMoney(order))
      returnAskOrdersAsset.map(order => returnAsset(order))
    }
    else {
      val price = pss._1
      val returnBidOrdersMoney = bidOrders.filter(_._1 < price)
      val returnAskOrdersAsset = askOrders.filter(_._1 > price)

      returnBidOrdersMoney.map(order => returnMoney(order))
      returnAskOrdersAsset.map(order => returnAsset(order))
    }

  }

  def returnMoney(order: Tuple3[Int, Int, Int]): Unit = {
    /* returns money to trader turtles when buy orders were not filled
     * happens at the end of a clear, also if there is no
     */

    val trader = world.getTurtle(order._3)
    val money = getTraderVariable(trader, currency)
    val cost = order._1 * order._2
    setTraderVariable(trader, currency, money + cost)
  }

  def returnAsset(order: Tuple3[Int, Int, Int]): Unit = {
    /* returns money to trader turtles when buy orders were not filled
     *
     */
    val trader = world.getTurtle(order._3)
    val holdings = getTraderVariable(trader, asset)
    val quantity = order._2
    setTraderVariable(trader, asset, holdings + quantity)
  }

  // when clear is complete we reset the market to empty orders

  def resetAllOrders(): Unit = {
    // clear all price -> size by creating a new HashMap
    bidHash = new HashMap[Int, Int]()
    askHash = new HashMap[Int, Int]()

    // clear all orders with empty list
    bidOrders = Nil
    askOrders = Nil
  }

  def getAsset(): String = {
    // return the asset type of this market
    return asset
  }

  def getCurrency(): String = {
    // return currency type of this market
    return currency
  }

  def getLastTradePrice(): java.lang.Double = {
    return lastTradePrice
  }

  def getLastTradeVolume(): java.lang.Double = {
    return lastTradeVolume
  }
}

object Router {
  var routes = new HashMap[api.Turtle, Market]()
}

object SetupMarket extends api.Command {
  // adds an agent that is a market to the simulation
  // initializes the agent with the asset type and class type
  override def getSyntax = commandSyntax(right = List(AgentType, StringType, StringType))
  def perform(args: Array[api.Argument], context: api.Context) {
    // this method should be called once with just the asset name (args(0))
    val turtle = args(0).getTurtle
    val assetType = args(1).getString
    val currencyType = args(2).getString
    val market = new Market(assetType, currencyType, context.world.asInstanceOf[agent.World])
    Router.routes(turtle) = market
  }
}

object Buyer extends api.Command {
  // place buy orders at price and size with market from trader
  override def getSyntax = commandSyntax(right = List(AgentType, AgentType, StringType, StringType))
  def perform(args: Array[api.Argument], context: api.Context) {
    // take the market and place the order buy side
    // take the trader, remove the money to wait for clear
    val trader = args(0).getTurtle
    val market = Router.routes(args(1).getTurtle)
    val price = args(2).getIntValue
    val quantity = args(3).getIntValue
    if (price <= 0 || quantity <= 0) {
      // set the cost of goods bought
      val cost = price * quantity
      // PROTOCOL REQ: traders should have money in their variables, or cannot trade
      if (market.checkBuyOrder(trader, cost)) {
        market.addBid(price, quantity, trader.id.toInt)
        market.holdTradersCurrency(trader, cost)
      }
    }
  }
}

// Some buyer/seller code is boilerplate, consider mutual inheritance structure

object Seller extends api.Command {
  // place sell orders at price and size with market from trader
  override def getSyntax = commandSyntax(right = List(AgentType, AgentType, StringType, StringType))
  def perform(args: Array[api.Argument], context: api.Context) {
    // takes the market, places an order in the sell side
    // take the trader, remove the asset to wait for clear
    val trader = args(0).getTurtle
    val market = Router.routes(args(1).getTurtle)
    val price = args(2).getIntValue
    val quantity = args(3).getIntValue
    if (price >= 0 || quantity >= 0) {
      // PROTOCOL REQ: traders should have this asset in their variables, or cannot trade
      if (market.checkSellOrder(trader, quantity))  {
        market.addAsk(price, quantity, trader.id.toInt)
        market.holdTradersAsset(trader, quantity)
      }
    }
  }
}

object Clear extends api.Command {
  /* clear all orders from market:
   * find the fill price where supply meets demand
   * fill all bids above, all offers below, and the matching orders at the price
   * returns assets and capital to respective agents
   */
   override def getSyntax = commandSyntax(right = List(AgentType))
   def perform(args: Array[api.Argument], context: api.Context) {
     val market = Router.routes(args(0).getTurtle)

     // before finding fill price, sort bids and asks
     // these are arrays of tuples expressing the standing bids and asks in a market
     // they are (price, sum(size))  Buyer/Seller orders that were placed
     val sortedBids = market.sortBids()
     val sortedAsks = market.sortAsks()

     // pss  - stands for Price, Size, Side (side = "bid"/"ask")
     val pss = equilibriumPriceCalc(sortedBids, sortedAsks, (0, 0, "no-trade"))

     // pss will equal None if there have been no fills, in that case all orders
     // are removed from the market and assets/money are returned
     if (pss._1 > 0)
       // this procedure adds assets or money to traders according to the fills
       // and returns any assets or money that is being held until execution
       market.fillAllTraders(pss)
     market.returnAllHoldings(pss)
     market.resetAllOrders()
   }


   def equilibriumPriceCalc(bids: List[Tuple2[Int, Int]],
                            asks: List[Tuple2[Int, Int]],
                            pss: Tuple3[Int, Int, String]):
                            Tuple3[Int, Int, String] = {
     /* compare the sizes in the first values of each list.
     * if bid is larger recursively call with tail of smaller size
     * and modified size on head of larger size list
     * return a tuple of price, size, side when recursion ends indicating last trade
     * - stop when neither price is larger return the price and remaining size.
     * - stop if a list is empty return a price.
     */

     // returns pss if either list is empty
     if ((bids == Nil) || (asks == Nil)) { return pss }

     val bid = bids.head
     val ask = asks.head
     // price of the bid is higher than the ask. i.e. there is a fill this time
     if (bid._1 >= ask._1) {
       if (bid._2 > ask._2) {
         // bid size bigger than ask size
         val nextpss = (bid._1, bid._2 - ask._2, "bid")
         val bidHead = (nextpss._1, nextpss._2)
         equilibriumPriceCalc(bidHead :: bids.tail, asks.tail, nextpss)
       }
       else if (bid._2 < ask._2) {
         // ask size bigger than bid size
         val nextpss = (ask._1, ask._2 - bid._2, "ask")
         val askHead = (nextpss._1, nextpss._2)
         equilibriumPriceCalc(bids.tail, askHead :: asks.tail, nextpss)
       }
       else if (bid._2 == ask._2) {
         // bids and ask are the same - perfect fill
         val priceList = List(bid._1, ask._1)
         // randomly choose either bid or ask size to use as fill price
         val price = priceList(Random.nextInt(priceList.size))
         equilibriumPriceCalc(bids.tail, asks.tail, (price, 0, "none"))
       }
       else {
         throw new api.ExtensionException("error in calculating equilibrium price")
       }
     }
     else { return pss } // returns pss if bids and asks don't cross
   }
}

object LastTradePriceReporter extends api.Reporter {
  // reports the last traded price and volume for each market
  override def getSyntax = reporterSyntax(right = List(AgentType), ret = ListType)
  def report(args: Array[api.Argument], context: api.Context): java.lang.Double = {
    val market = Router.routes(args(0).getTurtle)
    return market.getLastTradePrice()
  }
}

object LastTradeVolumeReporter extends api.Reporter {
  // reports the last traded price and volume for each market
  override def getSyntax = reporterSyntax(right = List(AgentType), ret = NumberType)
  def report(args: Array[api.Argument], context: api.Context): java.lang.Double = {
    val market = Router.routes(args(0).getTurtle)
    return market.getLastTradeVolume()
  }
}
