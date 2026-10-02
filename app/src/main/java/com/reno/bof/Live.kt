package com.reno.bof

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time prices for Reno's BOF.
 *  - NIFTY, BANKNIFTY, SENSEX: live index price from Yahoo (NSE/BSE real-time), polled every few seconds
 *  - Gold + forex: Swissquote public spot quotes
 *  - BTC: Binance
 *  - CRUDE: no free live feed (stays on Yahoo futures, ~10 min delayed)
 * Candle history is extended with live ticks; gold futures history is shifted onto spot.
 */
object Live {
    private val yahooLive = mapOf("NIFTY" to "^NSEI", "BANKNIFTY" to "^NSEBANK", "SENSEX" to "^BSESN")
    private val swissquote = mapOf(
        "GOLD" to "XAU/USD", "EURUSD" to "EUR/USD", "GBPUSD" to "GBP/USD",
        "USDJPY" to "USD/JPY", "USDINR" to "USD/INR"
    )
    private val binance = mapOf("BTC" to "BTCUSDT")
    private val futures = setOf("GOLD")

    private val ticks = HashMap<String, ArrayDeque<Pair<Long, Double>>>()
    private val cache = HashMap<String, Pair<Long, Double>>()
    private val offsets = HashMap<String, Double>()

    fun isCrypto(name: String) = name in binance
    fun hasLive(name: String) = name in yahooLive || name in swissquote || name in binance

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    @Synchronized
    private fun record(name: String, t: Long, p: Double) {
        val q = ticks.getOrPut(name) { ArrayDeque() }
        q.addLast(t to p)
        while (q.size > 6000 || (q.isNotEmpty() && q.first().first < t - 8 * 3600)) q.removeFirst()
    }

    @Synchronized
    private fun ticksSince(name: String, t: Long): List<Pair<Long, Double>> =
        ticks[name]?.filter { it.first >= t } ?: emptyList()

    /** Latest real-time price, or null if unavailable / market closed. */
    fun spot(name: String): Double? {
        val now = System.currentTimeMillis()
        synchronized(this) { cache[name]?.let { if (now - it.first < 1500) return it.second } }
        val p = try {
            when (name) {
                in yahooLive -> {
                    val sym = URLEncoder.encode(yahooLive[name], "UTF-8")
                    val meta = JSONObject(get("https://query1.finance.yahoo.com/v8/finance/chart/$sym?interval=1m&range=1d"))
                        .getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
                    val t = meta.optLong("regularMarketTime", 0) * 1000
                    if (now - t > 5 * 60 * 1000L) null else meta.getDouble("regularMarketPrice")
                }
                in binance -> JSONObject(get("https://api.binance.com/api/v3/ticker/price?symbol=${binance[name]}"))
                    .getString("price").toDouble()
                in swissquote -> {
                    val arr = JSONArray(get("https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/${swissquote[name]}"))
                    val first = arr.getJSONObject(0)
                    if (now - first.optLong("ts", now) > 10 * 60 * 1000L) null
                    else {
                        val pr = first.getJSONArray("spreadProfilePrices").getJSONObject(0)
                        (pr.getDouble("bid") + pr.getDouble("ask")) / 2
                    }
                }
                else -> null
            }
        } catch (e: Exception) { null } ?: return null
        synchronized(this) { cache[name] = now to p }
        record(name, now / 1000, p)
        return p
    }

    /** Real Binance candles for BTC. */
    fun cryptoSeries(name: String, interval: String): Series? {
        val sym = binance[name] ?: return null
        val iv = if (interval == "60m") "1h" else interval
        return try {
            val arr = JSONArray(get("https://api.binance.com/api/v3/klines?symbol=$sym&interval=$iv&limit=1000"))
            val list = ArrayList<Candle>(arr.length())
            for (i in 0 until arr.length()) {
                val k = arr.getJSONArray(i)
                list.add(Candle(k.getLong(0) / 1000, k.getString(1).toDouble(), k.getString(2).toDouble(),
                    k.getString(3).toDouble(), k.getString(4).toDouble(), k.getString(5).toDouble()))
            }
            if (list.isEmpty()) null else Series(list, 0, list.last().c, true, true)
        } catch (e: Exception) { null }
    }

    /** Extend a series with live ticks (and shift gold futures onto spot). */
    fun patch(name: String, s: Series, tfSec: Long): Pair<Series, Boolean> {
        val price = spot(name) ?: return s to false
        if (s.candles.isEmpty()) return s to false
        var cs = s.candles
        if (name in futures) {
            val off = price - cs.last().c
            synchronized(this) { offsets[name] = off }
            cs = cs.map { Candle(it.t, it.o + off, it.h + off, it.l + off, it.c + off, it.v) }
        }
        val out = ArrayList(cs)
        for ((t, p) in ticksSince(name, out.last().t)) {
            val start = Math.floorDiv(t, tfSec) * tfSec
            val last = out.last()
            if (start <= last.t) {
                out[out.size - 1] = Candle(last.t, last.o, max(last.h, p), min(last.l, p), p, last.v)
            } else {
                out.add(Candle(start, last.c, max(last.c, p), min(last.c, p), p, 0.0))
            }
        }
        return Series(out, s.gmtOffset, price, true, s.hasVolume) to true
    }
}
