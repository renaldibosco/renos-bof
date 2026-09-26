package com.reno.bof

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

data class Series(
    val candles: List<Candle>,
    val gmtOffset: Long,     // seconds, e.g. 19800 for IST
    val price: Double,
    val open: Boolean,       // market open right now
    val hasVolume: Boolean
)

/** Live candles from Yahoo Finance (free, no key). */
object Market {

    /** App name -> Yahoo symbol */
    val symbols: LinkedHashMap<String, String> = linkedMapOf(
        "NIFTY" to "^NSEI",
        "BANKNIFTY" to "^NSEBANK",
        "SENSEX" to "^BSESN",
        "CRUDE" to "CL=F"
    )

    /** Timeframe -> (interval, range, higher-timeframe interval, seconds) */
    data class Tf(val interval: String, val range: String, val htf: String, val seconds: Long)

    val timeframes: Map<String, Tf> = mapOf(
        "1m" to Tf("1m", "5d", "5m", 60),
        "5m" to Tf("5m", "5d", "15m", 300),
        "15m" to Tf("15m", "5d", "60m", 900)
    )

    fun fetch(yahoo: String, interval: String, range: String): Series {
        val enc = URLEncoder.encode(yahoo, "UTF-8")
        var last: Exception? = null
        for (host in listOf("query1", "query2")) {
            try {
                val url = URL(
                    "https://$host.finance.yahoo.com/v8/finance/chart/$enc" +
                            "?interval=$interval&range=$range&includePrePost=false"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
                )
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val code = conn.responseCode
                if (code != 200) throw Exception("Market data error $code")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                return parse(body)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("Could not load market data")
    }

    private fun parse(body: String): Series {
        val result = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val meta = result.getJSONObject("meta")
        val ts = result.optJSONArray("timestamp") ?: JSONArray()
        val q = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.getJSONArray("open")
        val h = q.getJSONArray("high")
        val l = q.getJSONArray("low")
        val c = q.getJSONArray("close")
        val v = q.optJSONArray("volume")

        val list = ArrayList<Candle>(ts.length())
        for (i in 0 until ts.length()) {
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i)) continue
            val vol = if (v == null || v.isNull(i)) 0.0 else v.getDouble(i)
            list.add(Candle(ts.getLong(i), o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i), vol))
        }

        val gmt = meta.optLong("gmtoffset", 19800)
        val price = meta.optDouble("regularMarketPrice", list.lastOrNull()?.c ?: 0.0)
        val now = System.currentTimeMillis() / 1000
        val regular = meta.optJSONObject("currentTradingPeriod")?.optJSONObject("regular")
        val open = regular != null &&
                now >= regular.optLong("start", 0) && now <= regular.optLong("end", 0)
        val hasVolume = list.isNotEmpty() && list.count { it.v > 0 } > list.size / 2
        return Series(list, gmt, price, open, hasVolume)
    }
}
