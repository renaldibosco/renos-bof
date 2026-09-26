# 💛 Reno's BOF

Live **Breakout Failure (BOF)** scanner for Android: Nifty 50, Bank Nifty, Sensex and Crude.

**Download:** https://github.com/renaldibosco/renos-bof/releases/latest/download/RenosBOF.apk

## What it does
- Pulls live 1m / 5m / 15m candles and draws them on a TradingView-style chart
  (TradingView Lightweight Charts™).
- Draws the key levels: **PDH / PDL**, **Camarilla H3 H4 L3 L4**, **opening range (first 15 min)**, plus **VWAP**.
- Detects a **BOF** when price breaks a level and then closes back on the other side:
  - **Bearish BOF**: breaks above, closes back below (trapped buyers)
  - **Bullish BOF**: breaks below, closes back above (trapped sellers)
- Scores every BOF out of **6** confluence factors:
  1. Higher timeframe also rejected the level
  2. Weak breakout (low volume, or less than 0.5 ATR past the level for indices, which have no volume data)
  3. RSI divergence or extreme
  4. Rejection candle
  5. VWAP rejection
  6. Camarilla H4/L4 failure
- Gives entry, stop loss (beyond the failed breakout) and a 1:2 target.
- **Phone alerts** for scores of 4+ (you can change this), checked every minute from 9:15 AM to 3:30 PM, even with the app closed.

## Install
1. Open the download link on the phone and install. Allow "Install unknown apps".
   If Play Protect warns you, tap **More details → Install anyway**.
2. Open the app, allow notifications, and turn on **Phone alerts**.
3. **Oppo, Vivo, Realme, Xiaomi:** App info → Battery → **Allow background activity** and **Allow auto launch**.

## Notes
- Data comes from Yahoo Finance for free and can lag TradingView by up to about a minute.
- Crude is NYMEX WTI (CL=F), because MCX has no free feed.
- This is a data tool, not investment advice.

Built by Pablo Reinaldez.
