package org.nlogo.auction

import org.nlogo.{ agent, api, nvm }
import api.Syntax._
import api.ScalaConversions._  // implicits
import scala.collection.mutable.HashMap
import scala.utils.Random

def setTurtleVariable(turtle: agent.Turtle, varName: String, value: AnyRef): Unit = {
  /* Sets the turtles variables by names, looking at breeds first then all turtles
   * finds the index to handle the string
   */
  var vn = turtle.world.breedsOwnIndexOf(varName)
  if (vn < 0) {
    var vn = turtle.world.turtlesOwnIndexOf(varName)
  }
  if (vn >= 0) {
    turtle.setTurtleVariable(vn, value)
  } else {
    throw new ExtensionException(I18N.errors.get("org.nlogo.agent.Agent.breedDoesNotOwnVariable",
                                                  turtle.toString(), varName))
  }
}

class AuctionExtension extends api.DefaultClassManager {
  def load(manager: api.PrimitiveManager) {
    manager.addPrimitive("setup-market", SetupMarket)
    manager.addPrimitive("buy", Buyer)
    manager.addPrimitive("sell", Seller)
    manager.addPrimitive("clear", Clearing)
    manager.addPrimitive("last-trade", LastTradeReporter)
  }
}

class Market(asset: String, context: api.Context) extends agent.Turtle {
  /* Market is an agent that has methods for handling orders
   * we use a HashMap so we can do incremental updates with repeating prices
   * we should only have a single price key with size to find the fill price
   */

  resetAllOrders() // initializes empty HashMaps for bids/asks
  var lastTradePrice = new Int
  val world = context.getAgent.world.asInstanceOf[agent.World]
  setTurtleVariable(this, "asset", asset)
  val agentManager = new agent.AgentManagement()

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

  // next two methods hold assets or cash

  def holdTradersAsset(turtle: agent.Turtle, quantity: Int): Unit = {
    // removes traders asset until execution is complete
    val holdings = trader.getVariable(asset)
    setTurtleVariable(trader, asset,  holdings - quantity)
  }

  def holdTradersCash(turtle: agent.Turtle, cost: Int): Unit = {
    // removes traders cash until execution is complete
    val cash = trader.getVariable("cash")
    setTurtleVariable(trader, "cash", cash - cost)
  }

  // next two methods sort price, size for finding the trade price

  def sortBids(): List = {
    /* turn our hashmap into a list of tuples for finding price
     * sorts highest bidding price first, which is the first to fill
     */
    bidHash.iterator.toList.sortBy(- _._1)
  }

  def sortAsks(): List = {
    /* turn our hashmap into a list of tuples for finding price
     * sorts lowest asking price first, which is the first to fill
     */
    askHash.iterator.toList.sortBy(_._1)
  }

  // next seven methods used in clearing orders

  def fillAllTraders(pss: Tuple3[Int, Int, String]): Unit = {
    /* if pss does not exist, that means there is no trades
     * pss includes the price that all traders will be filled at
     * the size and side (ss) are only used for filling orders at the trade price
     * get the world and use traders' who numbers to fill them on orders
     */
    val price = pss._1
    val size = pss._2
    val side = pss._3

    if side != None {
      fillPartial(price, size, side)
      if side == "bid" {
        val fillBids = bidOrders.filter(_._1 > price)
        val fillAsks = askOrders.filter(_._1 <= price)
      }
      else if side == "ask" {
        val fillBids = bidOrders.filter(_._1 >= price)
        val fillAsks = askOrders.filter(_._1 < price)
      }
    }
    else {
      val fillBids = bidOrders.filter(_._1 >= price)
      val fillAsks = askOrders.filter(_._1 <= price)
    }

    fillBids.map(order => fillOrderBid(order, price))
    fillAsks.map(order => fillOrderAsk(order, price))
  }

  def fillPartial(price: Int, size: Int, side: String): Unit = {
    /* the final fill price with pss should fill a partial on each order
     * at that price with this side.
     * these orders are filled pro-rata as partial fills
     */
    if side == "buy" {
      val partFillBids = bidOrders.filter(_._1 == price)
      // fill a percent of each order, last gets the remainder
      val totalToFill = bidHash.get(price) - size
      val percentToFill = totalToFill / bidHash.get(price)
      // only get the sizes of the orders
      val partFillBidSizes = partFillBids.map(_._2)
      // get a list of pro-rata sizes
      val proportionateFills = partFillBidSizes.map(size =>
                                          math.round(size * percentToFill))
      // there may be rounding errors, so sum the list and get difference
      val sizeGap = size - proportionateFills.sum()
      // the biggest order will fill the gap of size fills vs pro-rata
      val gapFillIdx = proportionateFills.indexOf(proportionateFills.max)
      val finalFills = proportionateFills.updated(gapFillIdx,
                                      proportionateFills(gapFillIdx) + sizeGap)
      val bidOrdersFills = (partFillBids zip finalFills)
      bidOrdersFills.map{ case (o, f) => fillOrderPartialBid(o, f, price) }
      }
    if side == "ask" {
      val partFillAsks = askOrders.filter(_._1 == price)
      // fill a percent of each order, last gets the remainder
      val totalToFill = askHash.get(price) - size
      val percentToFill = totalToFill / askHash.get(price)
      // only get the sizes of the orders
      val partFillAskSizes = partFillAsks.map(_._2)
      // get a list of pro-rata sizes
      val proportionateFills = partFillAskSizes.map(size =>
                                          math.round(size * percentToFill))
       // there may errors caused by rounding, so sum the list and get difference
      val sizeGap = size - proportionateFills.sum()
      // the biggest order will fill the gap of size fills vs pro-rata
      val gapFillIdx = proportionateFills.indexOf(proportionateFills.max)
      val finalFills = proportionateFills.updated(gapFillIdx,
                                      proportionateFills(gapFillIdx) + sizeGap)
      val askOrdersFills = (partFillAsks zip finalFills)
      askOrdersFills.map{ case (o, f) => fillOrderPartialAsks(o, f, price) }
     }

  }

  // orders are price, size, who number - use who number to find turtle to fill

  def fillOrderBid(order: Tuple3[Int, Int, Int], price: Int): Unit = {
    /* fills a single buy order that has been placed
     * maps to all buy orders above trade price and, if side == sell at price
     * delivers assets to traders and returns difference of cash
     */
     // gets the trader associated with the id of the order
    val trader = agentManager.getTurtle(order._3)

   }

  // TODO Fill orders

  def fillOrderAsk(order: Tuple3[Int, Int, Int], price: Int): Unit = {
    /* fills a single sell order that has been placed
     * maps to all sell orders below trade price and, if side == buy at price
     * delivers cash to traders
     */
    val trader = agentManager.getTurtle(order._3)

  }

  def fillOrderPartialBid(order: Tuple3[Int, Int, Int], fillSize: Int,  price: Int): Unit = {
    /*
     *
     */
    val trader = agentManager.getTurtle(order._3)

  }

  def fillOrderPartialAsk(order: Tuple3[Int, Int, Int], price: Int): Unit = {
    /*
     *
     */
    val trader = agentManager.getTurtle(order._3)

  }

  def returnAllHoldings(pss: Option[Tuple3[Int, Int, String]]): Unit = {
    /* returns all holdings above the price level for asks and
     * all the holdings below the price level for bids
     */
    if pss == None {
      val returnBidOrdersCash = bidOrders
      val returnAskOrdersAsset = askOrders
    }
    else {
      val price = pss._1
      val returnBidOrdersCash = bidOrders.filter(_._1 < price)
      val returnAskOrdersAsset = askOrders.filter(_._1 > price)
    }

    returnBidOrdersCash.map(order => returnCash(order))
    returnAskOrdersAsset.map(order => returnAsset(order))

  }

  def returnCash(order: Tuple3[Int, Int, Int]): Unit = {
    /* returns cash to trader turtles when buy orders were not filled
     * happens at the end of a clear, also if there is no
     */

    val trader = agentManager.getTurtle(order._3)
    val cash = trader.getVariable("cash")
    val cost = order._1 * order._2
    setTurtleVariable(trader, "cash", cash + cost)
  }

  def returnAsset(order: Tuple3[Int, Int, Int]): Unit = {
    /* returns cash to trader turtles when buy orders were not filled
     *
     */
    val trader = agentManager.getTurtle(order._3)
    val holdings = trader.getVariable(asset)
    val quantity = order._2
    setTurtleVariable(trader, asset, holdings + quantity)
  }


  def resetAllOrders(): Unit = {
    // clear all price -> size by creating a new HashMap
    val bidHash = new HashMap[Int, Int]()
    val askHash = new HashMap[Int, Int]()

    // clear all orders with empty list
    val bidOrders = Nil
    val askOrders = Nil

    // with each clear, calculate a volume and take the price for last trade
    var lastTradePrice = 0
    var lastTradeVolume = 0
  }

  def getAsset(): String = {
    // return the asset type of this market
    return asset
  }

  def getLastTrade(): List {
    return List(lastTradePrice, lastTradeVolume)
  }

}

object SetupMarket extends api.Command {
  // adds an agent that is a market to the simulation
  // initializes the agent with the asset type and class type
  override def getSyntax = reporterSyntax(right = List(StringType), ret = ListType)
  def peform(Array[api.Argument], context: api.Context) {
    // this method should be called with just the asset name
    val market = new Market(args(0))
    // TODO: create a turtle here that is a market

  }

}

object Buyer extends api.Command {
  // place buy orders at price and size with market from trader
  override def getSyntax = commandSyntax(right = List(api.Agent | NumberType))
  def perform(Array[api.Argument], context: api.Context) {
    // take the market and place the order buy side
    // take the trader, remove the cash to wait for clear
    val trader = args(0)
    val market = args(1)
    val price = args(2).getIntValue
    val quantity = args(3).getIntValue
    if (price <= 0 || quantity <= 0)
      throw new api.ExtensionException("price and quantity must be positive")
      // Do we throw an Exception here or just drop the order?

    // set the cost of goods bought
    val cost = price * quantity
    // PROTOCOL REQ: traders should have cash in their variables, or cannot trade
    if (cost < trader.getVariable("cash")) {
      market.addBid(price, quantity, trader.id)
      market.holdTradersCash(trader, cost)
    }
  }
}

// Some buyer/seller code is boilerplate, consider mutual inheritance structure

object Seller extends api.Command {
  // place sell orders at price and size with market from trader
  override def getSyntax = commandSyntax(right = List(api.Agent | NumberType))
  def perform(Array[api.Argument], context: api.Context) {
    // takes the market, places an order in the sell side
    // take the trader, remove the asset to wait for clear
    val trader = args(0)
    val market = args(1)
    val price = args(2).getIntValue
    val quantity = args(3).getIntValue
    if (price <= 0 || quantity <= 0)
      throw new api.ExtensionException("price and quantity must be positive")
      // Do we throw an Exception here or just drop the order?
    // find what asset we are trading
    asset = market.getAsset()
    // PROTOCOL REQ: traders should have this asset in their variables, or cannot trade
    if (quantity < trader.getVariable(asset)) {
      market.addAsk(price, quantity, trader.id)
      market.holdTradersAsset(trader, quantity)
    }

}

object clear extends api.Command {
  /* clear all orders from market:
   * find the fill price where supply meets demand
   * fill all bids above, all offers below, and the matching orders at the price
   * returns assets and capital to respective agents
   */
   override def getSyntax = commandSyntax(right = List(api.Agent | NumberType))
   def perform(Array[api.Argument], context: api.Context) {
     val market = args(0)

     // before finding fill price, sort bids and asks
     // these are arrays of tuples expressing the standing bids and asks in a market
     // they are (price, sum(size))  Buyer/Seller orders that were placed
     val sortedBids = market.sortBids()
     val sortedAsks = market.sortAsks()

     // pss  - stands for Price, Size, Side (side = "bid"/"ask")
     val pss = equilibriumPriceCalc(sortedBids, sortedAsks)

     // pss will equal None if there have been no fills, in that case all orders
     // are removed from the market and assets/cash are returned
     if pss != None
       // this procedure adds assets or cash to traders according to the fills
       // and returns any assets or cash that is being held until execution
       market.fillAllTraders(pss)
     market.returnAllHoldings(pss)
     market.resetAllOrders()


   def equilibriumPriceCalc(bids: List, asks: List,
                            pss: Option[Tuple3[Int, Int, String]]):
                            Tuple3[Int, Int, String] = {
     /* compare the sizes in the first values of each list.
     * if bid is larger recursively call with tail of smaller size
     * and modified size on head of larger size list
     * return a tuple of price, size, side when recursion ends indicating last trade
     * - stop when neither price is larger return the price and remaining size.
     * - stop if a list is empty return a price.
     */
     val bid = bids.head
     val ask = asks.head
     // returns pss if either list is empty
     if (bids == Nil) || (asks == Nil) { return pss }

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
         equilibriumPriceCalc(bids.tail, askHead :: asks.tail, pss)
       }
       else if (bid._2 == ask._2) {
         // bids and ask are the same - perfect fill
         val priceList = List(bid._1, ask._1)
         // randomly choose either bid or ask size to use as fill price
         val price = priceList(Random.nextInt(priceList.size))
         equilibriumPriceCalc(bids.tail, asks.tail, (price, 0, None))
       }
     }
     else { return pss } // returns pss if bids and asks don't cross
   }
}

object LastTradeReporter extends api.DefaultReporter {
  // reports the last traded price and volume for each market
  def report(args: Array[api.Argument]) {
    return market.getLastTrade()
  }
}
