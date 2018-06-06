extensions [ auction ]

turtles-own [ cash asset price account ]
globals [ trade-price cleared-volume stop-indicator]
breed [ producers producer ]
breed [ consumers consumer ]
breed [ markets market ]

markets-own [ name ]

to setup
  ca
  set stop-indicator 0
  set trade-price (cost + utility) / 2
  create-producers 1 [
    set cash init-account
    set asset init-assets
    set account cash + asset * trade-price
    setxy (account * max-pxcor / (account-limit * 2))  5
    set shape "plant"
    set color green
    set price int (cost * (1 + random-float %-producer-return))
  ]
  create-consumers 1 [
    set cash init-account
    set asset init-assets
    set account cash + asset * trade-price
    setxy (account * max-pxcor / (account-limit * 2)) -5
    set shape "person"
    set color pink
    set price int (utility / (1 + random-float %-consumer-return))
  ]
  create-markets 1 [
    auction:setup-market self "asset" "cash"
    set name "asset"
    set shape "target"
    set color blue
    set size 4
    setxy 46 0
  ]
  set trade-price (cost + utility) / 2
  reset-ticks
end

to go

  ; first do exchange activity
  ask producers [
    ; sell into market, trading asset for cash
    sell
  ]
  ask consumers [
    ; buy from market, trading cash for asset
    buy
  ]

  ; clear all orders
  ask markets [
    ; all orders will exit market, asset and currency will trade
    auction:clear self
    ; get the last trade price after clearing
    set trade-price auction:last-trade-price one-of markets
    set cleared-volume auction:last-trade-volume one-of markets
    if trade-price = 0 [
      ; To prevent price of 0, in the case there is no trade yet
      set trade-price (cost + utility) / 2
    ]
  ]

  ask turtles [
    if breed != markets [
      ; everybody should update account value
      set account cash + asset * trade-price
      setxy (account * max-pxcor / (account-limit * 2)) ycor
    ]
  ]

  ; now activity is economic, produce or consume
  ask producers [
    if account < account-limit [
      ; get asset, spend cash
      produce
    ]
  ]
  ask consumers [
    if account < account-limit [
      ; get cash, spend asset
      consume
    ]
  ]

  ; lastly replicate this type in the market with enough account
  if count producers <= limit-of-type [
    ask producers [
      replicate
    ]
  ]
  if count consumers <= limit-of-type [
    ask consumers [
      replicate
    ]
  ]

  ; volatility and trend add price movement to our runs
  ; each tick represents a month for these parameters

  if volatility? [
    ; this turns the simulation into a stochastic process
    set utility utility + random-normal 0 (utility * volatility / 12)
    set cost cost + random-normal 0 (cost * volatility / 12)
  ]

  if trend? [
    ; this adds a slope to the underlying values
    set utility utility * (1 + trend / 12)
    set cost cost * (1 + trend / 12)
    if ticks > 1800 [
      ; weird stuff happens with exponential growth: just stop it now
      set utility 150
      set cost 50
      stop
    ]
  ]

  ifelse cleared-volume <= 0 [
    set stop-indicator stop-indicator + 1
  ] [
    set stop-indicator 0
  ]

  if stop-indicator > 50 [
    stop
  ]

  tick

end

to sell ; turtle procedure, for producers
  ;sell in market
  let trade-size int asset * sales
  auction:sell self one-of markets price trade-size
  ; when markets clear for sell, they do the equivalent of this:
  ; set account account + money
  ; set assets assets - sold-assets
end

to buy ; turtle procedure, for consumers
  ; buy in market
  let trade-size int cash * consumption-rate / price
  auction:buy self one-of markets price trade-size
  ; when markets clear for buy, they do the equivalent of this:
  ; set assets assets + assets-bought
  ; set account account - money-spent
end

to produce ; turtle procedure, for producers
  ; produce in economy: spend cost on volume of production
  let production-cost cash * production-rate
  let assets-produced production-cost / cost
  set asset asset + assets-produced
  set cash cash - production-cost
end

to consume ; turtle procedure, for consumers
  ; consume in economy: gain utility on volume of goods
  let assets-consumed asset * turnover
  let asset-value assets-consumed * utility
  set cash cash + asset-value
  set asset asset - assets-consumed
end

to replicate ; turtle procedure, for producers and consumers
  if cash > reproduce-limit [
    set cash cash - reproduce-costs
    hatch 1 [
      ; give this trader some cash but no asset - net cash stays the same
      set cash reproduce-costs
      set asset 0
      set account cash + asset * trade-price
      ; create a new price for this new trader and a location
      ifelse breed = producers [
        set price int (cost * (1 + random-float %-producer-return))
        setxy (account * max-pxcor / (account-limit * 2)) abs random-ycor
      ] [
        if breed = consumers [
          set price int (utility / (1 + random-float %-consumer-return))
          setxy (account * max-pxcor / (account-limit * 2)) (- abs random-ycor)
        ]
      ]
    ]
  ]
end
@#$#@#$#@
GRAPHICS-WINDOW
415
10
729
157
-1
-1
6.0
1
10
1
1
1
0
1
0
1
0
50
-11
11
1
1
1
ticks
15.0

BUTTON
80
130
165
163
NIL
setup
NIL
1
T
OBSERVER
NIL
NIL
NIL
NIL
1

BUTTON
260
130
345
163
NIL
go
T
1
T
OBSERVER
NIL
NIL
NIL
NIL
1

SLIDER
15
10
200
43
init-account
init-account
0
100000
10000.0
100
1
NIL
HORIZONTAL

SLIDER
15
90
200
123
utility
utility
0
200
125.0
1
1
NIL
HORIZONTAL

SLIDER
15
50
200
83
%-consumer-return
%-consumer-return
0
1
0.25
.01
1
NIL
HORIZONTAL

SLIDER
15
210
200
243
turnover
turnover
0
1
0.04
.01
1
NIL
HORIZONTAL

SLIDER
215
10
400
43
init-assets
init-assets
0
1000
0.0
1
1
NIL
HORIZONTAL

SLIDER
15
170
200
203
consumption-rate
consumption-rate
0
1
0.06
.01
1
NIL
HORIZONTAL

SLIDER
215
90
400
123
cost
cost
0
200
75.0
1
1
NIL
HORIZONTAL

SLIDER
220
210
405
243
sales
sales
0
1
0.04
.01
1
NIL
HORIZONTAL

SLIDER
220
170
405
203
production-rate
production-rate
0
1
0.06
.01
1
NIL
HORIZONTAL

SLIDER
215
50
400
83
%-producer-return
%-producer-return
0
1
0.25
.01
1
NIL
HORIZONTAL

PLOT
15
295
280
465
accounts
NIL
NIL
0.0
10.0
0.0
10.0
true
true
"" ""
PENS
"produce" 1.0 0 -2674135 true "" "plot sum [cash] of producers + sum [asset] of producers * trade-price"
"consume" 1.0 0 -13345367 true "" "plot sum [cash] of consumers + sum [asset] of consumers * trade-price"

SLIDER
220
250
405
283
reproduce-limit
reproduce-limit
100
10000
10000.0
1
1
NIL
HORIZONTAL

SLIDER
15
250
200
283
reproduce-costs
reproduce-costs
50
5000
3050.0
1
1
NIL
HORIZONTAL

MONITOR
290
295
373
340
trade-price
trade-price
17
1
11

MONITOR
376
296
471
341
cleared-volume
cleared-volume
17
1
11

PLOT
290
345
470
465
number of agents
NIL
NIL
0.0
10.0
0.0
10.0
true
false
"" ""
PENS
"producers" 1.0 0 -2674135 true "" "plot count producers"
"consumers" 1.0 0 -13345367 true "" "plot count consumers"

PLOT
480
295
725
465
trade-price
NIL
NIL
0.0
10.0
50.0
120.0
true
false
"" ""
PENS
"default" 1.0 0 -16777216 true "" "plot trade-price"

SLIDER
540
170
725
203
limit-of-type
limit-of-type
2
1000
155.0
1
1
NIL
HORIZONTAL

BUTTON
170
130
255
163
go-once
go
NIL
1
T
OBSERVER
NIL
NIL
NIL
NIL
1

SLIDER
540
210
725
243
account-limit
account-limit
10000
1000000
130000.0
10000
1
NIL
HORIZONTAL

SWITCH
420
170
530
203
volatility?
volatility?
1
1
-1000

SWITCH
420
210
530
243
trend?
trend?
1
1
-1000

SLIDER
420
250
565
283
volatility
volatility
0
1
0.14
.01
1
NIL
HORIZONTAL

SLIDER
570
250
725
283
trend
trend
0
.1
0.01
.01
1
NIL
HORIZONTAL

@#$#@#$#@
## WHAT IS IT?

This is a replicating producer vs. consumer model that creates cyclical price movement.

With the right parameters, prices converge more as more traders enter the market. This demonstrates an optimal price equilibrium found through market efficiency even with non-intelligent agents.

The theory and model is built upon the ideas from:

Allocative Efficiency of Markets with Zero Intelligence Traders: Market as a Partial Substitute for Individual Rationality. Dhananjay K. Gode; Shyam Sunder. The Journal of Political Economy. Vol 101, No. 1 Feb., 1993.

It does not identically model this paper, the purpose being to find other interesting behavior in price movements with alternative parameter sets.


## HOW IT WORKS

This model randomly selects a price for each producer to sell at throughout the course of the simulation. It gives a maximum of a chosen %-return from the cost of production. The consumer will buy at a price that has been randomly selected and maximally gives a chosen %-return for the utility (in currency units) when consumed. Each producer and consumer will only trade at a % of their account and holdings determined by sliders.

The traders place their orders by price and size into the market, which clears all orders at the end of every tick. Their fill price can be different than the price they ask which is due to the auction mechanism

## HOW TO USE IT

Set the parameters of how much to trade in the in the sliders. Model will implement visuals but will not utilize locations for functional dynamics. Plotting will display the value of the accounts and assets of all producers and consumers as well as the number of producers and consumers and the trade prices.

## THINGS TO NOTICE

Producer and Consumer accounts to assets reach equilibrium ratios in the model with stable prices. Price movement happens as each producer and consumer gets higher returns trading. Eventually many traders set more stable liquity to the market over longer time horizons.

## THINGS TO TRY

For starters, try commenting out replicate in the Go procedure. This creates a more restrictive price movement between only two prices. Prevent prices from crossing (utility < cost) and see how the producer and consumers accounts respond. 

The model is implementing an auction framework that can be reused and extended to multiple use cases. Auctions have many emergent properties driven by cyclical price movements and liquities in search of equilibriums and stability. Auctions can be highly unstable as well, usually due to human preferences and circumstances of devised need.

## EXTENDING THE MODEL

This model can be extended to many agents with varied price and return patterns. The model could extend to demonstrate the competitive advantage of trading between networks, localities, or industries in free markets.

## NETLOGO FEATURES

This model uses the auction extension, which is an implementation of a discrete time double sided batch auction. Orders are not matched but the entire market is processed together and cleared in a pool.

## RELATED MODELS

Related models include Hotelling's Law, which demonstrates game-theory in establishing markets, and bidding market, where buyers and sellers try to get the best prices in a competitive trading environment.

## CREDITS AND REFERENCES

This model is a recreation of models implemented by Unicorn Markets LLC.

The code and the extension were written by Michael Tamillow with guidance from the Center for Connected Learning and Northwestern University
@#$#@#$#@
default
true
0
Polygon -7500403 true true 150 5 40 250 150 205 260 250

airplane
true
0
Polygon -7500403 true true 150 0 135 15 120 60 120 105 15 165 15 195 120 180 135 240 105 270 120 285 150 270 180 285 210 270 165 240 180 180 285 195 285 165 180 105 180 60 165 15

arrow
true
0
Polygon -7500403 true true 150 0 0 150 105 150 105 293 195 293 195 150 300 150

box
false
0
Polygon -7500403 true true 150 285 285 225 285 75 150 135
Polygon -7500403 true true 150 135 15 75 150 15 285 75
Polygon -7500403 true true 15 75 15 225 150 285 150 135
Line -16777216 false 150 285 150 135
Line -16777216 false 150 135 15 75
Line -16777216 false 150 135 285 75

bug
true
0
Circle -7500403 true true 96 182 108
Circle -7500403 true true 110 127 80
Circle -7500403 true true 110 75 80
Line -7500403 true 150 100 80 30
Line -7500403 true 150 100 220 30

butterfly
true
0
Polygon -7500403 true true 150 165 209 199 225 225 225 255 195 270 165 255 150 240
Polygon -7500403 true true 150 165 89 198 75 225 75 255 105 270 135 255 150 240
Polygon -7500403 true true 139 148 100 105 55 90 25 90 10 105 10 135 25 180 40 195 85 194 139 163
Polygon -7500403 true true 162 150 200 105 245 90 275 90 290 105 290 135 275 180 260 195 215 195 162 165
Polygon -16777216 true false 150 255 135 225 120 150 135 120 150 105 165 120 180 150 165 225
Circle -16777216 true false 135 90 30
Line -16777216 false 150 105 195 60
Line -16777216 false 150 105 105 60

car
false
0
Polygon -7500403 true true 300 180 279 164 261 144 240 135 226 132 213 106 203 84 185 63 159 50 135 50 75 60 0 150 0 165 0 225 300 225 300 180
Circle -16777216 true false 180 180 90
Circle -16777216 true false 30 180 90
Polygon -16777216 true false 162 80 132 78 134 135 209 135 194 105 189 96 180 89
Circle -7500403 true true 47 195 58
Circle -7500403 true true 195 195 58

circle
false
0
Circle -7500403 true true 0 0 300

circle 2
false
0
Circle -7500403 true true 0 0 300
Circle -16777216 true false 30 30 240

cow
false
0
Polygon -7500403 true true 200 193 197 249 179 249 177 196 166 187 140 189 93 191 78 179 72 211 49 209 48 181 37 149 25 120 25 89 45 72 103 84 179 75 198 76 252 64 272 81 293 103 285 121 255 121 242 118 224 167
Polygon -7500403 true true 73 210 86 251 62 249 48 208
Polygon -7500403 true true 25 114 16 195 9 204 23 213 25 200 39 123

cylinder
false
0
Circle -7500403 true true 0 0 300

dot
false
0
Circle -7500403 true true 90 90 120

face happy
false
0
Circle -7500403 true true 8 8 285
Circle -16777216 true false 60 75 60
Circle -16777216 true false 180 75 60
Polygon -16777216 true false 150 255 90 239 62 213 47 191 67 179 90 203 109 218 150 225 192 218 210 203 227 181 251 194 236 217 212 240

face neutral
false
0
Circle -7500403 true true 8 7 285
Circle -16777216 true false 60 75 60
Circle -16777216 true false 180 75 60
Rectangle -16777216 true false 60 195 240 225

face sad
false
0
Circle -7500403 true true 8 8 285
Circle -16777216 true false 60 75 60
Circle -16777216 true false 180 75 60
Polygon -16777216 true false 150 168 90 184 62 210 47 232 67 244 90 220 109 205 150 198 192 205 210 220 227 242 251 229 236 206 212 183

fish
false
0
Polygon -1 true false 44 131 21 87 15 86 0 120 15 150 0 180 13 214 20 212 45 166
Polygon -1 true false 135 195 119 235 95 218 76 210 46 204 60 165
Polygon -1 true false 75 45 83 77 71 103 86 114 166 78 135 60
Polygon -7500403 true true 30 136 151 77 226 81 280 119 292 146 292 160 287 170 270 195 195 210 151 212 30 166
Circle -16777216 true false 215 106 30

flag
false
0
Rectangle -7500403 true true 60 15 75 300
Polygon -7500403 true true 90 150 270 90 90 30
Line -7500403 true 75 135 90 135
Line -7500403 true 75 45 90 45

flower
false
0
Polygon -10899396 true false 135 120 165 165 180 210 180 240 150 300 165 300 195 240 195 195 165 135
Circle -7500403 true true 85 132 38
Circle -7500403 true true 130 147 38
Circle -7500403 true true 192 85 38
Circle -7500403 true true 85 40 38
Circle -7500403 true true 177 40 38
Circle -7500403 true true 177 132 38
Circle -7500403 true true 70 85 38
Circle -7500403 true true 130 25 38
Circle -7500403 true true 96 51 108
Circle -16777216 true false 113 68 74
Polygon -10899396 true false 189 233 219 188 249 173 279 188 234 218
Polygon -10899396 true false 180 255 150 210 105 210 75 240 135 240

house
false
0
Rectangle -7500403 true true 45 120 255 285
Rectangle -16777216 true false 120 210 180 285
Polygon -7500403 true true 15 120 150 15 285 120
Line -16777216 false 30 120 270 120

leaf
false
0
Polygon -7500403 true true 150 210 135 195 120 210 60 210 30 195 60 180 60 165 15 135 30 120 15 105 40 104 45 90 60 90 90 105 105 120 120 120 105 60 120 60 135 30 150 15 165 30 180 60 195 60 180 120 195 120 210 105 240 90 255 90 263 104 285 105 270 120 285 135 240 165 240 180 270 195 240 210 180 210 165 195
Polygon -7500403 true true 135 195 135 240 120 255 105 255 105 285 135 285 165 240 165 195

line
true
0
Line -7500403 true 150 0 150 300

line half
true
0
Line -7500403 true 150 0 150 150

pentagon
false
0
Polygon -7500403 true true 150 15 15 120 60 285 240 285 285 120

person
false
0
Circle -7500403 true true 110 5 80
Polygon -7500403 true true 105 90 120 195 90 285 105 300 135 300 150 225 165 300 195 300 210 285 180 195 195 90
Rectangle -7500403 true true 127 79 172 94
Polygon -7500403 true true 195 90 240 150 225 180 165 105
Polygon -7500403 true true 105 90 60 150 75 180 135 105

plant
false
0
Rectangle -7500403 true true 135 90 165 300
Polygon -7500403 true true 135 255 90 210 45 195 75 255 135 285
Polygon -7500403 true true 165 255 210 210 255 195 225 255 165 285
Polygon -7500403 true true 135 180 90 135 45 120 75 180 135 210
Polygon -7500403 true true 165 180 165 210 225 180 255 120 210 135
Polygon -7500403 true true 135 105 90 60 45 45 75 105 135 135
Polygon -7500403 true true 165 105 165 135 225 105 255 45 210 60
Polygon -7500403 true true 135 90 120 45 150 15 180 45 165 90

sheep
false
15
Circle -1 true true 203 65 88
Circle -1 true true 70 65 162
Circle -1 true true 150 105 120
Polygon -7500403 true false 218 120 240 165 255 165 278 120
Circle -7500403 true false 214 72 67
Rectangle -1 true true 164 223 179 298
Polygon -1 true true 45 285 30 285 30 240 15 195 45 210
Circle -1 true true 3 83 150
Rectangle -1 true true 65 221 80 296
Polygon -1 true true 195 285 210 285 210 240 240 210 195 210
Polygon -7500403 true false 276 85 285 105 302 99 294 83
Polygon -7500403 true false 219 85 210 105 193 99 201 83

square
false
0
Rectangle -7500403 true true 30 30 270 270

square 2
false
0
Rectangle -7500403 true true 30 30 270 270
Rectangle -16777216 true false 60 60 240 240

star
false
0
Polygon -7500403 true true 151 1 185 108 298 108 207 175 242 282 151 216 59 282 94 175 3 108 116 108

target
false
0
Circle -7500403 true true 0 0 300
Circle -16777216 true false 30 30 240
Circle -7500403 true true 60 60 180
Circle -16777216 true false 90 90 120
Circle -7500403 true true 120 120 60

tree
false
0
Circle -7500403 true true 118 3 94
Rectangle -6459832 true false 120 195 180 300
Circle -7500403 true true 65 21 108
Circle -7500403 true true 116 41 127
Circle -7500403 true true 45 90 120
Circle -7500403 true true 104 74 152

triangle
false
0
Polygon -7500403 true true 150 30 15 255 285 255

triangle 2
false
0
Polygon -7500403 true true 150 30 15 255 285 255
Polygon -16777216 true false 151 99 225 223 75 224

truck
false
0
Rectangle -7500403 true true 4 45 195 187
Polygon -7500403 true true 296 193 296 150 259 134 244 104 208 104 207 194
Rectangle -1 true false 195 60 195 105
Polygon -16777216 true false 238 112 252 141 219 141 218 112
Circle -16777216 true false 234 174 42
Rectangle -7500403 true true 181 185 214 194
Circle -16777216 true false 144 174 42
Circle -16777216 true false 24 174 42
Circle -7500403 false true 24 174 42
Circle -7500403 false true 144 174 42
Circle -7500403 false true 234 174 42

turtle
true
0
Polygon -10899396 true false 215 204 240 233 246 254 228 266 215 252 193 210
Polygon -10899396 true false 195 90 225 75 245 75 260 89 269 108 261 124 240 105 225 105 210 105
Polygon -10899396 true false 105 90 75 75 55 75 40 89 31 108 39 124 60 105 75 105 90 105
Polygon -10899396 true false 132 85 134 64 107 51 108 17 150 2 192 18 192 52 169 65 172 87
Polygon -10899396 true false 85 204 60 233 54 254 72 266 85 252 107 210
Polygon -7500403 true true 119 75 179 75 209 101 224 135 220 225 175 261 128 261 81 224 74 135 88 99

wheel
false
0
Circle -7500403 true true 3 3 294
Circle -16777216 true false 30 30 240
Line -7500403 true 150 285 150 15
Line -7500403 true 15 150 285 150
Circle -7500403 true true 120 120 60
Line -7500403 true 216 40 79 269
Line -7500403 true 40 84 269 221
Line -7500403 true 40 216 269 79
Line -7500403 true 84 40 221 269

wolf
false
0
Polygon -16777216 true false 253 133 245 131 245 133
Polygon -7500403 true true 2 194 13 197 30 191 38 193 38 205 20 226 20 257 27 265 38 266 40 260 31 253 31 230 60 206 68 198 75 209 66 228 65 243 82 261 84 268 100 267 103 261 77 239 79 231 100 207 98 196 119 201 143 202 160 195 166 210 172 213 173 238 167 251 160 248 154 265 169 264 178 247 186 240 198 260 200 271 217 271 219 262 207 258 195 230 192 198 210 184 227 164 242 144 259 145 284 151 277 141 293 140 299 134 297 127 273 119 270 105
Polygon -7500403 true true -1 195 14 180 36 166 40 153 53 140 82 131 134 133 159 126 188 115 227 108 236 102 238 98 268 86 269 92 281 87 269 103 269 113

x
false
0
Polygon -7500403 true true 270 75 225 30 30 225 75 270
Polygon -7500403 true true 30 75 75 30 270 225 225 270
@#$#@#$#@
NetLogo 6.0.3
@#$#@#$#@
@#$#@#$#@
@#$#@#$#@
@#$#@#$#@
@#$#@#$#@
default
0.0
-0.2 0 0.0 1.0
0.0 1 1.0 0.0
0.2 0 0.0 1.0
link direction
true
0
Line -7500403 true 150 150 90 180
Line -7500403 true 150 150 210 180
@#$#@#$#@
1
@#$#@#$#@
