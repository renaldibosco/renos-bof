package com.reno.bof

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Level(val name: String, val price: Double, val from: Int = 0)
data class Factor(val name: String, val ok: Boolean)

data class Signal(
    val index: Int,
    val t: Long,
    val bullish: Boolean,
    val level: Level,
    val factors: List<Factor>,
    val entry: Double,
    val stop: Double,
    val target: Double
) {
    val score: Int get() = factors.count { it.ok }
}

data class Analysis(
    val series: Series,
    val vwap: DoubleArray,
    val todayStart: Int,
    val levels: List<Level>,
    val signals: List<Signal>,
    val prevClose: Double
)

/**
 * Breakout Failure (BOF) engine.
 *
 * A BOF = price breaks a key level, then closes back on the other side.
 *   Bearish BOF: breaks ABOVE a level, closes back BELOW it (trapped buyers).
 *   Bullish BOF: breaks BELOW a level, closes back ABOVE it (trapped sellers).
 *
 * Key levels (per day): previous day high/low (PDH/PDL), Camarilla H3/H4/L3/L4
 * from previous day OHLC, opening range high/low (first 15 min), recent swing high/low.
 *
 * Each BOF is scored on 6 confluence factors (score 0-6):
 *   1. Higher timeframe also rejected the level
 *   2. Weak breakout (low volume, or tiny push < 0.5 ATR when no volume data)
 *   3. RSI divergence or RSI extreme at the breakout
 *   4. Rejection candle (long wick, or strong reversal body)
 *   5. VWAP rejection
 *   6. Camarilla H3/H4 or L3/L4 failure
 */
object Engine {
    private const val TOL = 0.0002 // 0.02% beyond the level counts as a break

    fun dayKey(t: Long, gmt: Long): Long = Math.floorDiv(t + gmt, 86400L)

    fun analyze(s: Series, htf: Series?): Analysis {
        val cs = s.candles
        val n = cs.size
        val vwap = DoubleArray(n)
        if (n < 20) return Analysis(s, vwap, 0, emptyList(), emptyList(), cs.firstOrNull()?.o ?: s.price)

        val rsi = rsi(cs)
        val atr = atr(cs)

        // Split candles into trading days
        val days = ArrayList<IntRange>()
        var start = 0
        for (i in 1..n) {
            if (i == n || dayKey(cs[i].t, s.gmtOffset) != dayKey(cs[start].t, s.gmtOffset)) {
                days.add(start until i)
                start = i
            }
        }

        val signals = ArrayList<Signal>()
        var todayLevels: List<Level> = emptyList()
        var prevClose = cs.first().o

        for (d in days.indices) {
            val range = days[d]
            // Session VWAP (time-weighted when the symbol has no volume, e.g. indices)
            var pv = 0.0
            var vv = 0.0
            for (i in range) {
                val tp = (cs[i].h + cs[i].l + cs[i].c) / 3
                val w = if (s.hasVolume) max(cs[i].v, 1.0) else 1.0
                pv += tp * w; vv += w
                vwap[i] = pv / vv
            }
            if (d == 0) continue

            val prev = days[d - 1]
            var pH = Double.NEGATIVE_INFINITY
            var pL = Double.POSITIVE_INFINITY
            for (i in prev) { pH = max(pH, cs[i].h); pL = min(pL, cs[i].l) }
            val pC = cs[prev.last].c
            val rng = pH - pL
            prevClose = pC

            val levels = arrayListOf(
                Level("PDH", pH), Level("PDL", pL),
                Level("H4", pC + rng * 1.1 / 2), Level("L4", pC - rng * 1.1 / 2),
                Level("H3", pC + rng * 1.1 / 4), Level("L3", pC - rng * 1.1 / 4)
            )
            val dayStartT = cs[range.first].t
            val orb = range.filter { cs[it].t < dayStartT + 15 * 60 }
            if (orb.isNotEmpty() && orb.last() < range.last) {
                levels.add(Level("ORH", orb.maxOf { cs[it].h }, orb.last() + 1))
                levels.add(Level("ORL", orb.minOf { cs[it].l }, orb.last() + 1))
            }
            if (d == days.lastIndex) todayLevels = levels
            val h4 = levels[2].price
            val l4 = levels[3].price

            val lastSig = HashMap<String, Int>()
            for (i in range) {
                if (i < 5) continue
                val cands = ArrayList<Pair<Level, Int>>() // level, direction (-1 bear, 1 bull, 0 both)
                for (lv in levels) if (i >= lv.from) cands.add(lv to 0)
                val a = max(0, i - 30)
                val b = i - 3
                if (b > a) {
                    var sh = Double.NEGATIVE_INFINITY
                    var sl = Double.POSITIVE_INFINITY
                    for (k in a..b) { sh = max(sh, cs[k].h); sl = min(sl, cs[k].l) }
                    cands.add(Level("Swing H", sh) to -1)
                    cands.add(Level("Swing L", sl) to 1)
                }
                for ((lv, dir) in cands) {
                    for (bull in booleanArrayOf(false, true)) {
                        if (dir == -1 && bull) continue
                        if (dir == 1 && !bull) continue
                        val bo = detect(cs, i, lv.price, bull, range.first) ?: continue
                        val key = lv.name + bull
                        val lastI = lastSig[key]
                        if (lastI != null && i - lastI < 6) continue
                        lastSig[key] = i
                        signals.add(
                            score(cs, i, bo, lv, bull, rsi, atr, vwap, s.hasVolume, htf, h4, l4)
                        )
                    }
                }
            }
        }

        // If two levels fail on the same candle, keep the best-scored one
        val best = signals.groupBy { it.t to it.bullish }.map { (_, v) -> v.maxBy { it.score } }
            .sortedBy { it.t }

        val todayStart = days.last().first
        return Analysis(s, vwap, todayStart, todayLevels, best, prevClose)
    }

    /** Returns the breakout candle index if candle i completes a BOF at level L. */
    private fun detect(cs: List<Candle>, i: Int, L: Double, bull: Boolean, dayFirst: Int): Int? {
        val c = cs[i]
        if (!bull) {
            // One-candle fakeout: wick above, close back below
            if (c.h > L * (1 + TOL) && c.c < L && cs[i - 1].c < L) return i
            // Closed above for 1-3 candles, then closed back below
            if (c.c < L) {
                for (k in 1..3) {
                    val j = i - k
                    if (j - 1 < dayFirst) break
                    var held = true
                    for (m in j until i) if (cs[m].c <= L) { held = false; break }
                    if (held && cs[j].c > L * (1 + TOL / 2) && cs[j - 1].c <= L) return j
                }
            }
        } else {
            if (c.l < L * (1 - TOL) && c.c > L && cs[i - 1].c > L) return i
            if (c.c > L) {
                for (k in 1..3) {
                    val j = i - k
                    if (j - 1 < dayFirst) break
                    var held = true
                    for (m in j until i) if (cs[m].c >= L) { held = false; break }
                    if (held && cs[j].c < L * (1 - TOL / 2) && cs[j - 1].c >= L) return j
                }
            }
        }
        return null
    }

    private fun score(
        cs: List<Candle>, i: Int, bo: Int, lv: Level, bull: Boolean,
        rsi: DoubleArray, atr: DoubleArray, vwap: DoubleArray, hasVolume: Boolean,
        htf: Series?, h4: Double, l4: Double
    ): Signal {
        val c = cs[i]
        val L = lv.price
        // Extreme of the failed breakout
        var ext = if (bull) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        var extI = bo
        for (k in bo..i) {
            if (!bull && cs[k].h > ext) { ext = cs[k].h; extI = k }
            if (bull && cs[k].l < ext) { ext = cs[k].l; extI = k }
        }

        // 1. Higher timeframe rejection
        var f1 = false
        if (htf != null) {
            val hc = htf.candles.lastOrNull { it.t <= c.t }
            if (hc != null) f1 = if (bull) hc.l < L && hc.c > L else hc.h > L && hc.c < L
        }

        // 2. Weak breakout
        val f2 = if (hasVolume) {
            val from = max(0, bo - 20)
            val avg = if (bo > from) (from until bo).sumOf { cs[it].v } / (bo - from) else 0.0
            avg > 0 && cs[bo].v < avg
        } else {
            abs(ext - L) < 0.5 * atr[i]
        }

        // 3. RSI divergence / extreme
        var f3 = if (bull) rsi[extI] <= 30 else rsi[extI] >= 70
        if (!f3) {
            val a = max(0, extI - 30)
            val b = extI - 3
            if (b > a) {
                var p = a
                for (k in a..b) {
                    if (!bull && cs[k].h > cs[p].h) p = k
                    if (bull && cs[k].l < cs[p].l) p = k
                }
                f3 = if (bull) cs[extI].l <= cs[p].l && rsi[extI] > rsi[p] + 1
                else cs[extI].h >= cs[p].h && rsi[extI] < rsi[p] - 1
            }
        }

        // 4. Rejection candle
        val body = abs(c.c - c.o)
        val rng = c.h - c.l
        val f4 = rng > 0 && (if (bull) {
            val wick = min(c.o, c.c) - c.l
            (wick >= 0.5 * rng && wick >= 1.5 * body) || (c.c > c.o && body >= 0.6 * rng)
        } else {
            val wick = c.h - max(c.o, c.c)
            (wick >= 0.5 * rng && wick >= 1.5 * body) || (c.c < c.o && body >= 0.6 * rng)
        })

        // 5. VWAP rejection
        val recentFrom = max(0, i - 2)
        val f5 = if (bull) {
            val low = (recentFrom..i).minOf { cs[it].l }
            c.c > vwap[i] && low <= vwap[i] * 1.0005
        } else {
            val high = (recentFrom..i).maxOf { cs[it].h }
            c.c < vwap[i] && high >= vwap[i] * 0.9995
        }

        // 6. Camarilla failure
        val f6 = if (bull) lv.name == "L3" || lv.name == "L4" || (ext < l4 && c.c > l4)
        else lv.name == "H3" || lv.name == "H4" || (ext > h4 && c.c < h4)

        val factors = listOf(
            Factor("Higher timeframe rejected", f1),
            Factor(if (hasVolume) "Weak breakout volume" else "Weak breakout push", f2),
            Factor("RSI divergence / extreme", f3),
            Factor("Rejection candle", f4),
            Factor("VWAP rejection", f5),
            Factor("Camarilla H4/L4 failure", f6)
        )

        val buffer = 0.1 * atr[i]
        val stop = if (bull) ext - buffer else ext + buffer
        val risk = abs(c.c - stop)
        val target = if (bull) c.c + 2 * risk else c.c - 2 * risk
        return Signal(i, c.t, bull, lv, factors, c.c, stop, target)
    }

    private fun rsi(cs: List<Candle>, p: Int = 14): DoubleArray {
        val r = DoubleArray(cs.size) { 50.0 }
        var g = 0.0
        var l = 0.0
        for (i in 1 until cs.size) {
            val ch = cs[i].c - cs[i - 1].c
            val up = max(ch, 0.0)
            val dn = max(-ch, 0.0)
            if (i <= p) {
                g += up; l += dn
                if (i == p) { g /= p; l /= p; r[i] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l) }
            } else {
                g = (g * (p - 1) + up) / p
                l = (l * (p - 1) + dn) / p
                r[i] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
            }
        }
        return r
    }

    private fun atr(cs: List<Candle>, p: Int = 14): DoubleArray {
        val a = DoubleArray(cs.size)
        var cur = 0.0
        for (i in cs.indices) {
            val tr = if (i == 0) cs[i].h - cs[i].l else max(
                cs[i].h - cs[i].l,
                max(abs(cs[i].h - cs[i - 1].c), abs(cs[i].l - cs[i - 1].c))
            )
            cur = if (i < p) (cur * i + tr) / (i + 1) else (cur * (p - 1) + tr) / p
            a[i] = cur
        }
        return a
    }

    /** Everything the screen needs, as JSON. */
    fun toJson(name: String, tf: String, an: Analysis, live: Boolean = false): String {
        val s = an.series
        val off = 19800L // show every chart in Indian time (IST)
        val root = JSONObject()
        root.put("name", name).put("tf", tf).put("open", s.open).put("hasVolume", s.hasVolume).put("live", live)
        val price = if (s.candles.isNotEmpty()) s.candles.last().c else s.price
        root.put("price", price)
        root.put("prevClose", an.prevClose)
        root.put("updated", System.currentTimeMillis())

        val candles = JSONArray()
        for (c in s.candles) {
            candles.put(
                JSONObject().put("time", c.t + off).put("open", c.o).put("high", c.h)
                    .put("low", c.l).put("close", c.c)
            )
        }
        root.put("candles", candles)

        val vw = JSONArray()
        for (i in an.todayStart until s.candles.size) {
            vw.put(JSONObject().put("time", s.candles[i].t + off).put("value", an.vwap[i]))
        }
        root.put("vwap", vw)

        val lv = JSONArray()
        for (l in an.levels) lv.put(JSONObject().put("name", l.name).put("price", l.price))
        root.put("levels", lv)

        val sigs = JSONArray()
        val todayT = if (s.candles.isNotEmpty()) s.candles[an.todayStart].t else 0L
        for (g in an.signals) {
            val f = JSONArray()
            for (x in g.factors) f.put(JSONObject().put("name", x.name).put("ok", x.ok))
            sigs.put(
                JSONObject().put("time", g.t + off).put("bull", g.bullish)
                    .put("level", g.level.name).put("levelPrice", g.level.price)
                    .put("score", g.score).put("factors", f)
                    .put("entry", g.entry).put("stop", g.stop).put("target", g.target)
                    .put("today", g.t >= todayT)
            )
        }
        root.put("signals", sigs)
        return root.toString()
    }
}
