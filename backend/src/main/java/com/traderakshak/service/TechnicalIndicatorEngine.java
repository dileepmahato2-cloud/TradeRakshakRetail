package com.traderakshak.service;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class TechnicalIndicatorEngine {

    // ── RSI (Wilder's Smoothing) ──────────────────────────────────────────────
    public double rsi(List<Double> closes, int period) {
        if (closes == null || closes.size() < period + 1) return 50.0;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double d = closes.get(i) - closes.get(i - 1);
            if (d > 0) gain += d; else loss -= d;
        }
        double ag = gain / period, al = loss / period;
        for (int i = period + 1; i < closes.size(); i++) {
            double d = closes.get(i) - closes.get(i - 1);
            ag = (ag * (period - 1) + Math.max(d, 0)) / period;
            al = (al * (period - 1) + Math.max(-d, 0)) / period;
        }
        if (al == 0) return 100.0;
        return round1(100.0 - 100.0 / (1 + ag / al));
    }

    public List<Double> rsiSeries(List<Double> closes, int period) {
        List<Double> result = new ArrayList<>();
        if (closes == null || closes.size() < period + 1) return result;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double d = closes.get(i) - closes.get(i - 1);
            if (d > 0) gain += d; else loss -= d;
        }
        double ag = gain / period, al = loss / period;
        result.add(al == 0 ? 100.0 : round1(100.0 - 100.0 / (1 + ag / al)));
        for (int i = period + 1; i < closes.size(); i++) {
            double d = closes.get(i) - closes.get(i - 1);
            ag = (ag * (period - 1) + Math.max(d, 0)) / period;
            al = (al * (period - 1) + Math.max(-d, 0)) / period;
            result.add(al == 0 ? 100.0 : round1(100.0 - 100.0 / (1 + ag / al)));
        }
        return result;
    }

    // ── EMA ───────────────────────────────────────────────────────────────────
    public double ema(List<Double> prices, int period) {
        if (prices == null || prices.size() < period) return prices != null && !prices.isEmpty() ? prices.get(prices.size()-1) : 0;
        double k = 2.0 / (period + 1);
        double e = avg(prices.subList(0, period));
        for (int i = period; i < prices.size(); i++) e = prices.get(i) * k + e * (1 - k);
        return round2(e);
    }

    public List<Double> emaSeries(List<Double> prices, int period) {
        List<Double> result = new ArrayList<>();
        if (prices == null || prices.size() < period) return result;
        double k = 2.0 / (period + 1);
        double e = avg(prices.subList(0, period));
        result.add(round2(e));
        for (int i = period; i < prices.size(); i++) {
            e = prices.get(i) * k + e * (1 - k);
            result.add(round2(e));
        }
        return result;
    }

    // ── SMA ───────────────────────────────────────────────────────────────────
    public double sma(List<Double> prices, int period) {
        if (prices == null || prices.size() < period) return prices != null && !prices.isEmpty() ? last(prices) : 0;
        return round2(avg(prices.subList(prices.size() - period, prices.size())));
    }

    public List<Double> smaSeries(List<Double> prices, int period) {
        List<Double> result = new ArrayList<>();
        if (prices == null || prices.size() < period) return result;
        for (int i = period - 1; i < prices.size(); i++) {
            result.add(round2(avg(prices.subList(i - period + 1, i + 1))));
        }
        return result;
    }

    // ── WMA ───────────────────────────────────────────────────────────────────
    public double wma(List<Double> prices, int period) {
        if (prices == null || prices.size() < period) return 0;
        List<Double> slice = prices.subList(prices.size() - period, prices.size());
        double num = 0, den = 0;
        for (int i = 0; i < period; i++) { num += slice.get(i) * (i + 1); den += (i + 1); }
        return round2(num / den);
    }

    // ── VWAP ──────────────────────────────────────────────────────────────────
    public double vwap(List<Double> highs, List<Double> lows, List<Double> closes, List<Double> volumes) {
        if (closes == null || closes.isEmpty()) return 0;
        double pv = 0, tv = 0;
        for (int i = 0; i < closes.size(); i++) {
            double tp = (highs.get(i) + lows.get(i) + closes.get(i)) / 3;
            double v  = volumes.get(i);
            pv += tp * v; tv += v;
        }
        return tv > 0 ? round2(pv / tv) : 0;
    }

    // ── MACD (proper signal line via EMA of MACD history) ─────────────────────
    public double[] macd(List<Double> closes, int fast, int slow, int signal) {
        if (closes == null || closes.size() < slow + signal) return new double[]{0, 0, 0};
        List<Double> fastEma = emaSeries(closes, fast);
        List<Double> slowEma = emaSeries(closes, slow);
        int offset = slow - fast;
        List<Double> macdLine = new ArrayList<>();
        for (int i = 0; i < slowEma.size(); i++) {
            macdLine.add(fastEma.get(i + offset) - slowEma.get(i));
        }
        if (macdLine.size() < signal) return new double[]{last(macdLine), last(macdLine), 0};
        double sig = ema(macdLine, signal);
        double lastMacd = last(macdLine);
        return new double[]{round2(lastMacd), round2(sig), round2(lastMacd - sig)};
    }

    public List<double[]> macdSeries(List<Double> closes, int fast, int slow, int signal) {
        List<double[]> result = new ArrayList<>();
        if (closes == null || closes.size() < slow + signal) return result;
        List<Double> fastEma = emaSeries(closes, fast);
        List<Double> slowEma = emaSeries(closes, slow);
        int offset = slow - fast;
        List<Double> macdLine = new ArrayList<>();
        for (int i = 0; i < slowEma.size(); i++) {
            macdLine.add(fastEma.get(i + offset) - slowEma.get(i));
        }
        List<Double> sigLine = emaSeries(macdLine, signal);
        int sigOffset = signal - 1;
        for (int i = 0; i < sigLine.size(); i++) {
            double m = macdLine.get(i + sigOffset);
            double s = sigLine.get(i);
            result.add(new double[]{round2(m), round2(s), round2(m - s)});
        }
        return result;
    }

    // ── Bollinger Bands ───────────────────────────────────────────────────────
    public double[] bollingerBands(List<Double> closes, int period, double stdMult) {
        if (closes == null || closes.size() < period) return new double[]{0, 0, 0};
        List<Double> slice = closes.subList(closes.size() - period, closes.size());
        double mid = avg(slice);
        double std = stdDev(slice, mid);
        return new double[]{round2(mid + stdMult * std), round2(mid), round2(mid - stdMult * std)};
    }

    public double bollingerBandWidth(List<Double> closes, int period, double stdMult) {
        double[] bb = bollingerBands(closes, period, stdMult);
        return bb[1] > 0 ? round2((bb[0] - bb[2]) / bb[1] * 100) : 0;
    }

    public double bollingerPercentB(List<Double> closes, int period, double stdMult) {
        double[] bb = bollingerBands(closes, period, stdMult);
        double width = bb[0] - bb[2];
        if (width == 0) return 50;
        return round2((last(closes) - bb[2]) / width * 100);
    }

    // ── ATR ───────────────────────────────────────────────────────────────────
    public double atr(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (closes == null || closes.size() < period + 1) return 0;
        List<Double> trs = trueRanges(highs, lows, closes);
        return round2(avg(trs.subList(Math.max(0, trs.size()-period), trs.size())));
    }

    public List<Double> atrSeries(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        List<Double> trs = trueRanges(highs, lows, closes);
        List<Double> result = new ArrayList<>();
        if (trs.size() < period) return result;
        double atrVal = avg(trs.subList(0, period));
        result.add(round2(atrVal));
        for (int i = period; i < trs.size(); i++) {
            atrVal = (atrVal * (period - 1) + trs.get(i)) / period;
            result.add(round2(atrVal));
        }
        return result;
    }

    private List<Double> trueRanges(List<Double> highs, List<Double> lows, List<Double> closes) {
        List<Double> trs = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            double tr = Math.max(highs.get(i) - lows.get(i),
                        Math.max(Math.abs(highs.get(i) - closes.get(i-1)),
                                 Math.abs(lows.get(i)  - closes.get(i-1))));
            trs.add(tr);
        }
        return trs;
    }

    // ── ADX ───────────────────────────────────────────────────────────────────
    public double adx(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        double[] d = dmi(highs, lows, closes, period);
        return d[2];
    }

    // ── DMI (+DI, -DI, ADX) ──────────────────────────────────────────────────
    public double[] dmi(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (closes == null || closes.size() < period * 2) return new double[]{25.0, 25.0, 25.0};
        List<Double> pDMs = new ArrayList<>(), mDMs = new ArrayList<>(), trs = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            double upMove   = highs.get(i)  - highs.get(i-1);
            double downMove = lows.get(i-1) - lows.get(i);
            pDMs.add(upMove > downMove && upMove > 0 ? upMove : 0);
            mDMs.add(downMove > upMove && downMove > 0 ? downMove : 0);
            trs.add(Math.max(highs.get(i)-lows.get(i), Math.max(Math.abs(highs.get(i)-closes.get(i-1)), Math.abs(lows.get(i)-closes.get(i-1)))));
        }
        double atrV = avg(trs.subList(trs.size()-period, trs.size()));
        if (atrV == 0) return new double[]{25.0, 25.0, 25.0};
        double pDI = avg(pDMs.subList(pDMs.size()-period, pDMs.size())) / atrV * 100;
        double mDI = avg(mDMs.subList(mDMs.size()-period, mDMs.size())) / atrV * 100;
        double dx  = Math.abs(pDI - mDI) / (pDI + mDI + 0.001) * 100;
        return new double[]{round1(pDI), round1(mDI), round1(dx)};
    }

    // ── Supertrend ────────────────────────────────────────────────────────────
    public boolean supertrendBullish(List<Double> highs, List<Double> lows, List<Double> closes, int period, double multiplier) {
        if (closes.size() < period + 1) return true;
        double atrV = atr(highs, lows, closes, period);
        double hl2  = (last(highs) + last(lows)) / 2;
        return last(closes) > hl2 - multiplier * atrV;
    }

    public double[] supertrend(List<Double> highs, List<Double> lows, List<Double> closes, int period, double multiplier) {
        if (closes.size() < period + 2) return new double[]{last(closes), 1};
        List<Double> atrS = atrSeries(highs, lows, closes, period);
        if (atrS.isEmpty()) return new double[]{last(closes), 1};
        int start = closes.size() - atrS.size();
        double upperBand = 0, lowerBand = 0, superT = 0;
        int direction = 1;
        for (int i = 0; i < atrS.size(); i++) {
            int idx = start + i;
            double hl2 = (highs.get(idx) + lows.get(idx)) / 2;
            double newUpper = hl2 + multiplier * atrS.get(i);
            double newLower = hl2 - multiplier * atrS.get(i);
            if (i > 0) {
                upperBand = newUpper < upperBand || closes.get(idx - 1) > upperBand ? newUpper : upperBand;
                lowerBand = newLower > lowerBand || closes.get(idx - 1) < lowerBand ? newLower : lowerBand;
            } else {
                upperBand = newUpper;
                lowerBand = newLower;
            }
            if (i == 0) {
                direction = closes.get(idx) > upperBand ? 1 : -1;
            } else {
                if (direction == -1 && closes.get(idx) > upperBand) direction = 1;
                else if (direction == 1 && closes.get(idx) < lowerBand) direction = -1;
            }
            superT = direction == 1 ? lowerBand : upperBand;
        }
        return new double[]{round2(superT), direction};
    }

    // ── Stochastic Oscillator (%K, %D) ────────────────────────────────────────
    public double[] stochastic(List<Double> highs, List<Double> lows, List<Double> closes, int kPeriod, int dPeriod) {
        if (closes == null || closes.size() < kPeriod) return new double[]{50, 50};
        List<Double> kValues = new ArrayList<>();
        for (int i = kPeriod - 1; i < closes.size(); i++) {
            double hh = Double.MIN_VALUE, ll = Double.MAX_VALUE;
            for (int j = i - kPeriod + 1; j <= i; j++) {
                hh = Math.max(hh, highs.get(j));
                ll = Math.min(ll, lows.get(j));
            }
            double k = hh == ll ? 50 : (closes.get(i) - ll) / (hh - ll) * 100;
            kValues.add(k);
        }
        double lastK = last(kValues);
        double lastD = kValues.size() >= dPeriod ? sma(kValues, dPeriod) : lastK;
        return new double[]{round1(lastK), round1(lastD)};
    }

    // ── Stochastic RSI ────────────────────────────────────────────────────────
    public double[] stochasticRsi(List<Double> closes, int rsiPeriod, int stochPeriod) {
        if (closes.size() < rsiPeriod + stochPeriod + 10) return new double[]{50, 50};
        List<Double> rsiS = rsiSeries(closes, rsiPeriod);
        if (rsiS.size() < stochPeriod) return new double[]{50, 50};
        List<Double> recent = rsiS.subList(rsiS.size() - stochPeriod, rsiS.size());
        double minRsi = recent.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double maxRsi = recent.stream().mapToDouble(Double::doubleValue).max().orElse(100);
        double lastRsi = last(rsiS);
        double k = maxRsi == minRsi ? 50 : (lastRsi - minRsi) / (maxRsi - minRsi) * 100;
        return new double[]{round1(k), round1(k)};
    }

    // ── ROC (Rate of Change) ──────────────────────────────────────────────────
    public double roc(List<Double> closes, int period) {
        if (closes == null || closes.size() <= period) return 0;
        double prev = closes.get(closes.size() - 1 - period);
        return prev == 0 ? 0 : round2((last(closes) - prev) / prev * 100);
    }

    // ── Momentum ──────────────────────────────────────────────────────────────
    public double momentum(List<Double> closes, int period) {
        if (closes == null || closes.size() <= period) return 0;
        return round2(last(closes) - closes.get(closes.size() - 1 - period));
    }

    // ── Williams %R ───────────────────────────────────────────────────────────
    public double williamsR(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (closes == null || closes.size() < period) return -50;
        double hh = Double.MIN_VALUE, ll = Double.MAX_VALUE;
        for (int i = closes.size() - period; i < closes.size(); i++) {
            hh = Math.max(hh, highs.get(i));
            ll = Math.min(ll, lows.get(i));
        }
        if (hh == ll) return -50;
        return round1((hh - last(closes)) / (hh - ll) * -100);
    }

    // ── CCI (Commodity Channel Index) ─────────────────────────────────────────
    public double cci(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        if (closes == null || closes.size() < period) return 0;
        List<Double> typicalPrices = new ArrayList<>();
        for (int i = 0; i < closes.size(); i++) {
            typicalPrices.add((highs.get(i) + lows.get(i) + closes.get(i)) / 3);
        }
        List<Double> recent = typicalPrices.subList(typicalPrices.size() - period, typicalPrices.size());
        double tpMean = avg(recent);
        double meanDev = recent.stream().mapToDouble(tp -> Math.abs(tp - tpMean)).average().orElse(0);
        if (meanDev == 0) return 0;
        return round1((last(typicalPrices) - tpMean) / (0.015 * meanDev));
    }

    // ── Parabolic SAR ─────────────────────────────────────────────────────────
    public double[] parabolicSar(List<Double> highs, List<Double> lows, List<Double> closes,
                                  double afStart, double afStep, double afMax) {
        int n = closes.size();
        if (n < 3) return new double[]{last(closes), 1};
        boolean isLong = closes.get(1) > closes.get(0);
        double sar = isLong ? lows.get(0) : highs.get(0);
        double ep = isLong ? highs.get(1) : lows.get(1);
        double af = afStart;
        for (int i = 2; i < n; i++) {
            sar = sar + af * (ep - sar);
            if (isLong) {
                sar = Math.min(sar, Math.min(lows.get(i - 1), lows.get(i - 2)));
                if (highs.get(i) > ep) { ep = highs.get(i); af = Math.min(af + afStep, afMax); }
                if (lows.get(i) < sar) { isLong = false; sar = ep; ep = lows.get(i); af = afStart; }
            } else {
                sar = Math.max(sar, Math.max(highs.get(i - 1), highs.get(i - 2)));
                if (lows.get(i) < ep) { ep = lows.get(i); af = Math.min(af + afStep, afMax); }
                if (highs.get(i) > sar) { isLong = true; sar = ep; ep = highs.get(i); af = afStart; }
            }
        }
        return new double[]{round2(sar), isLong ? 1 : -1};
    }

    // ── Ichimoku Cloud ────────────────────────────────────────────────────────
    public double[] ichimoku(List<Double> highs, List<Double> lows, List<Double> closes,
                              int tenkanPeriod, int kijunPeriod, int senkouBPeriod) {
        double tenkan = midPoint(highs, lows, tenkanPeriod);
        double kijun  = midPoint(highs, lows, kijunPeriod);
        double senkouA = (tenkan + kijun) / 2;
        double senkouB = midPoint(highs, lows, senkouBPeriod);
        double chikou  = closes.size() >= kijunPeriod + 1
            ? closes.get(closes.size() - kijunPeriod - 1) : last(closes);
        return new double[]{round2(tenkan), round2(kijun), round2(senkouA), round2(senkouB), round2(chikou)};
    }

    private double midPoint(List<Double> highs, List<Double> lows, int period) {
        if (highs == null || highs.size() < period) return 0;
        double hh = Double.MIN_VALUE, ll = Double.MAX_VALUE;
        for (int i = highs.size() - period; i < highs.size(); i++) {
            hh = Math.max(hh, highs.get(i));
            ll = Math.min(ll, lows.get(i));
        }
        return (hh + ll) / 2;
    }

    // ── Keltner Channel ───────────────────────────────────────────────────────
    public double[] keltnerChannel(List<Double> highs, List<Double> lows, List<Double> closes,
                                    int emaPeriod, int atrPeriod, double multiplier) {
        double mid = ema(closes, emaPeriod);
        double atrV = atr(highs, lows, closes, atrPeriod);
        return new double[]{round2(mid + multiplier * atrV), round2(mid), round2(mid - multiplier * atrV)};
    }

    // ── Donchian Channel ──────────────────────────────────────────────────────
    public double[] donchianChannel(List<Double> highs, List<Double> lows, int period) {
        if (highs == null || highs.size() < period) return new double[]{0, 0, 0};
        double hh = Double.MIN_VALUE, ll = Double.MAX_VALUE;
        for (int i = highs.size() - period; i < highs.size(); i++) {
            hh = Math.max(hh, highs.get(i));
            ll = Math.min(ll, lows.get(i));
        }
        return new double[]{round2(hh), round2((hh + ll) / 2), round2(ll)};
    }

    // ── OBV (On-Balance Volume) ───────────────────────────────────────────────
    public double obv(List<Double> closes, List<Double> volumes) {
        if (closes == null || closes.size() < 2) return 0;
        double obvVal = 0;
        for (int i = 1; i < closes.size(); i++) {
            if (closes.get(i) > closes.get(i - 1)) obvVal += volumes.get(i);
            else if (closes.get(i) < closes.get(i - 1)) obvVal -= volumes.get(i);
        }
        return round2(obvVal);
    }

    public List<Double> obvSeries(List<Double> closes, List<Double> volumes) {
        List<Double> result = new ArrayList<>();
        if (closes == null || closes.size() < 2) return result;
        double obvVal = 0;
        result.add(0.0);
        for (int i = 1; i < closes.size(); i++) {
            if (closes.get(i) > closes.get(i - 1)) obvVal += volumes.get(i);
            else if (closes.get(i) < closes.get(i - 1)) obvVal -= volumes.get(i);
            result.add(round2(obvVal));
        }
        return result;
    }

    // ── MFI (Money Flow Index) ────────────────────────────────────────────────
    public double mfi(List<Double> highs, List<Double> lows, List<Double> closes,
                      List<Double> volumes, int period) {
        if (closes == null || closes.size() < period + 1) return 50;
        double posFlow = 0, negFlow = 0;
        for (int i = closes.size() - period; i < closes.size(); i++) {
            double tp = (highs.get(i) + lows.get(i) + closes.get(i)) / 3;
            double prevTp = (highs.get(i-1) + lows.get(i-1) + closes.get(i-1)) / 3;
            double rawFlow = tp * volumes.get(i);
            if (tp > prevTp) posFlow += rawFlow;
            else if (tp < prevTp) negFlow += rawFlow;
        }
        if (negFlow == 0) return 100;
        return round1(100 - 100 / (1 + posFlow / negFlow));
    }

    // ── Volume helpers ────────────────────────────────────────────────────────
    public double volumeChange(List<Double> volumes) {
        if (volumes == null || volumes.size() < 2) return 0;
        double prev = volumes.get(volumes.size() - 2);
        return prev == 0 ? 0 : round2((last(volumes) - prev) / prev * 100);
    }

    public double avgVolume(List<Double> volumes, int period) {
        return sma(volumes, period);
    }

    public boolean isVolumeSpikeDetected(List<Double> volumes, int period, double threshold) {
        if (volumes == null || volumes.size() < period + 1) return false;
        double avgVol = sma(volumes.subList(0, volumes.size() - 1), period);
        return avgVol > 0 && last(volumes) > avgVol * threshold;
    }

    public double volumeSpikeRatio(List<Double> volumes, int period) {
        if (volumes == null || volumes.size() < period + 1) return 1;
        double avgVol = sma(volumes.subList(0, volumes.size() - 1), period);
        return avgVol > 0 ? round2(last(volumes) / avgVol) : 1;
    }

    // ── Pivot Points (Standard) ───────────────────────────────────────────────
    public double[] pivotPoints(double high, double low, double close) {
        double pivot = (high + low + close) / 3;
        double r1 = 2 * pivot - low;
        double s1 = 2 * pivot - high;
        double r2 = pivot + (high - low);
        double s2 = pivot - (high - low);
        double r3 = high + 2 * (pivot - low);
        double s3 = low - 2 * (high - pivot);
        return new double[]{round2(pivot), round2(r1), round2(r2), round2(r3),
                            round2(s1), round2(s2), round2(s3)};
    }

    // ── Fibonacci Pivot Points ────────────────────────────────────────────────
    public double[] fibonacciPivotPoints(double high, double low, double close) {
        double pivot = (high + low + close) / 3;
        double range = high - low;
        return new double[]{round2(pivot),
            round2(pivot + 0.382 * range), round2(pivot + 0.618 * range), round2(pivot + range),
            round2(pivot - 0.382 * range), round2(pivot - 0.618 * range), round2(pivot - range)};
    }

    // ── CPR (Central Pivot Range) ─────────────────────────────────────────────
    public double[] cpr(double high, double low, double close) {
        double pivot = (high + low + close) / 3;
        double bc = (high + low) / 2;
        double tc = pivot + (pivot - bc);
        return new double[]{round2(pivot), round2(tc), round2(bc)};
    }

    public double cprWidth(double high, double low, double close) {
        double[] c = cpr(high, low, close);
        return c[0] > 0 ? round2(Math.abs(c[1] - c[2]) / c[0] * 100) : 0;
    }

    // ── Support & Resistance ──────────────────────────────────────────────────
    public double[] supportResistance(List<Double> highs, List<Double> lows, List<Double> closes, int lookback) {
        if (closes == null || closes.size() < lookback) return new double[]{0, 0, 0, 0};
        int start = closes.size() - lookback;
        double r1 = Double.MIN_VALUE, r2 = Double.MIN_VALUE;
        double s1 = Double.MAX_VALUE, s2 = Double.MAX_VALUE;
        for (int i = start; i < closes.size(); i++) {
            if (highs.get(i) > r1) { r2 = r1; r1 = highs.get(i); }
            else if (highs.get(i) > r2) r2 = highs.get(i);
            if (lows.get(i) < s1) { s2 = s1; s1 = lows.get(i); }
            else if (lows.get(i) < s2) s2 = lows.get(i);
        }
        return new double[]{round2(r1), round2(r2), round2(s1), round2(s2)};
    }

    // ── 52-Week High/Low ──────────────────────────────────────────────────────
    public double[] week52HighLow(List<Double> highs, List<Double> lows) {
        if (highs == null || highs.isEmpty()) return new double[]{0, 0};
        int period = Math.min(252, highs.size());
        double hh = Double.MIN_VALUE, ll = Double.MAX_VALUE;
        for (int i = highs.size() - period; i < highs.size(); i++) {
            hh = Math.max(hh, highs.get(i));
            ll = Math.min(ll, lows.get(i));
        }
        return new double[]{round2(hh), round2(ll)};
    }

    // ── Gap / Return / Price Change ───────────────────────────────────────────
    public double gapPercentage(List<Double> opens, List<Double> closes) {
        if (opens == null || opens.size() < 2 || closes.size() < 2) return 0;
        double prevClose = closes.get(closes.size() - 2);
        return prevClose == 0 ? 0 : round2((last(opens) - prevClose) / prevClose * 100);
    }

    public double returnPercentage(double entry, double exit) {
        return entry == 0 ? 0 : round2((exit - entry) / entry * 100);
    }

    public double priceChangePercentage(List<Double> closes, int periods) {
        if (closes == null || closes.size() <= periods) return 0;
        double prev = closes.get(closes.size() - 1 - periods);
        return prev == 0 ? 0 : round2((last(closes) - prev) / prev * 100);
    }

    // ── Price Pattern Detection ───────────────────────────────────────────────

    public boolean isHigherHigh(List<Double> highs) {
        if (highs.size() < 3) return false;
        return highs.get(highs.size()-1) > highs.get(highs.size()-2) &&
               highs.get(highs.size()-2) > highs.get(highs.size()-3);
    }

    public boolean isHigherLow(List<Double> lows) {
        if (lows.size() < 3) return false;
        return lows.get(lows.size()-1) > lows.get(lows.size()-2);
    }

    public boolean isLowerHigh(List<Double> highs) {
        if (highs.size() < 3) return false;
        return highs.get(highs.size()-1) < highs.get(highs.size()-2);
    }

    public boolean isLowerLow(List<Double> lows) {
        if (lows.size() < 3) return false;
        return lows.get(lows.size()-1) < lows.get(lows.size()-2) &&
               lows.get(lows.size()-2) < lows.get(lows.size()-3);
    }

    public boolean isBreakout(List<Double> closes, List<Double> highs, int lookback) {
        if (closes.size() < lookback + 1) return false;
        double prevHigh = highs.subList(highs.size() - 1 - lookback, highs.size() - 1)
            .stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return last(closes) > prevHigh;
    }

    public boolean isBreakdown(List<Double> closes, List<Double> lows, int lookback) {
        if (closes.size() < lookback + 1) return false;
        double prevLow = lows.subList(lows.size() - 1 - lookback, lows.size() - 1)
            .stream().mapToDouble(Double::doubleValue).min().orElse(999999);
        return last(closes) < prevLow;
    }

    // ── Signal Detection ──────────────────────────────────────────────────────

    public String detectRsiZone(List<Double> closes, int period) {
        double r = rsi(closes, period);
        if (r >= 70) return "OVERBOUGHT";
        if (r >= 60) return "BULLISH";
        if (r <= 30) return "OVERSOLD";
        if (r <= 40) return "BEARISH";
        return "NEUTRAL";
    }

    public String detectMacdSignal(List<Double> closes) {
        List<double[]> ms = macdSeries(closes, 12, 26, 9);
        if (ms.size() < 2) return "NEUTRAL";
        double[] prev = ms.get(ms.size() - 2);
        double[] curr = ms.get(ms.size() - 1);
        if (prev[0] <= prev[1] && curr[0] > curr[1]) return "BULLISH_CROSSOVER";
        if (prev[0] >= prev[1] && curr[0] < curr[1]) return "BEARISH_CROSSOVER";
        if (curr[2] > 0) return "BULLISH";
        if (curr[2] < 0) return "BEARISH";
        return "NEUTRAL";
    }

    public String detectEmaCrossover(List<Double> closes, int shortPeriod, int longPeriod) {
        if (closes == null || closes.size() < longPeriod + 2) return "NEUTRAL";
        List<Double> shortEma = emaSeries(closes, shortPeriod);
        List<Double> longEma = emaSeries(closes, longPeriod);
        if (shortEma.size() < 2 || longEma.size() < 2) return "NEUTRAL";
        int offset = longPeriod - shortPeriod;
        int si = shortEma.size() - 1;
        int li = longEma.size() - 1;
        double shortPrev = si - 1 >= offset ? shortEma.get(si - 1) : shortEma.get(Math.max(0, si - 1));
        double shortCurr = shortEma.get(si);
        double longPrev = longEma.get(li - 1);
        double longCurr = longEma.get(li);
        if (shortPrev <= longPrev && shortCurr > longCurr) return "GOLDEN_CROSS";
        if (shortPrev >= longPrev && shortCurr < longCurr) return "DEATH_CROSS";
        if (shortCurr > longCurr) return "BULLISH";
        return "BEARISH";
    }

    // ── Candlestick Patterns ──────────────────────────────────────────────────

    public boolean isHammer(double open, double high, double low, double close) {
        double body = Math.abs(close - open);
        double lower = Math.min(open, close) - low;
        double upper = high - Math.max(open, close);
        return lower >= 2 * body && upper <= 0.5 * body && body > 0;
    }

    public boolean isDoji(double open, double high, double low, double close) {
        double body = Math.abs(close - open);
        double range = high - low;
        return range > 0 && body / range < 0.1;
    }

    public boolean isBullishEngulfing(double prevOpen, double prevClose, double open, double close) {
        return prevClose < prevOpen && close > open && open <= prevClose && close >= prevOpen;
    }

    public boolean isBearishEngulfing(double prevOpen, double prevClose, double open, double close) {
        return prevClose > prevOpen && close < open && open >= prevClose && close <= prevOpen;
    }

    public boolean isMarubozu(double open, double high, double low, double close) {
        double body = Math.abs(close - open);
        double range = high - low;
        return range > 0 && body / range > 0.9;
    }

    public boolean isInvertedHammer(double open, double high, double low, double close) {
        double body = Math.abs(close - open);
        double upper = high - Math.max(open, close);
        double lower = Math.min(open, close) - low;
        return upper >= 2 * body && lower <= 0.5 * body && body > 0;
    }

    public boolean isShootingStar(double open, double high, double low, double close) {
        return isInvertedHammer(open, high, low, close) && close < open;
    }

    public boolean isSpinningTop(double open, double high, double low, double close) {
        double body = Math.abs(close - open);
        double range = high - low;
        double upper = high - Math.max(open, close);
        double lower = Math.min(open, close) - low;
        return range > 0 && body / range < 0.3 && upper > body && lower > body;
    }

    public boolean isMorningStar(List<Double> opens, List<Double> highs,
                                  List<Double> lows, List<Double> closes) {
        if (closes.size() < 3) return false;
        int n = closes.size();
        double o1 = opens.get(n-3), c1 = closes.get(n-3);
        double o2 = opens.get(n-2), c2 = closes.get(n-2), h2 = highs.get(n-2), l2 = lows.get(n-2);
        double o3 = opens.get(n-1), c3 = closes.get(n-1);
        boolean day1Bearish = c1 < o1 && Math.abs(c1 - o1) > 0;
        boolean day2Small = (h2 - l2) > 0 && Math.abs(c2 - o2) / (h2 - l2) < 0.3;
        boolean day3Bullish = c3 > o3 && c3 > (o1 + c1) / 2;
        return day1Bearish && day2Small && day3Bullish;
    }

    public boolean isEveningStar(List<Double> opens, List<Double> highs,
                                  List<Double> lows, List<Double> closes) {
        if (closes.size() < 3) return false;
        int n = closes.size();
        double o1 = opens.get(n-3), c1 = closes.get(n-3);
        double o2 = opens.get(n-2), c2 = closes.get(n-2), h2 = highs.get(n-2), l2 = lows.get(n-2);
        double o3 = opens.get(n-1), c3 = closes.get(n-1);
        boolean day1Bullish = c1 > o1 && Math.abs(c1 - o1) > 0;
        boolean day2Small = (h2 - l2) > 0 && Math.abs(c2 - o2) / (h2 - l2) < 0.3;
        boolean day3Bearish = c3 < o3 && c3 < (o1 + c1) / 2;
        return day1Bullish && day2Small && day3Bearish;
    }

    public boolean isThreeWhiteSoldiers(List<Double> opens, List<Double> closes) {
        if (closes.size() < 3) return false;
        int n = closes.size();
        for (int i = n - 3; i < n; i++) {
            if (closes.get(i) <= opens.get(i)) return false;
        }
        return closes.get(n-1) > closes.get(n-2) && closes.get(n-2) > closes.get(n-3)
            && opens.get(n-1) > opens.get(n-2) && opens.get(n-2) > opens.get(n-3);
    }

    public boolean isThreeBlackCrows(List<Double> opens, List<Double> closes) {
        if (closes.size() < 3) return false;
        int n = closes.size();
        for (int i = n - 3; i < n; i++) {
            if (closes.get(i) >= opens.get(i)) return false;
        }
        return closes.get(n-1) < closes.get(n-2) && closes.get(n-2) < closes.get(n-3)
            && opens.get(n-1) < opens.get(n-2) && opens.get(n-2) < opens.get(n-3);
    }

    // ── Crossover Detection ───────────────────────────────────────────────────

    public boolean crossAbove(List<Double> series1, List<Double> series2) {
        if (series1.size() < 2 || series2.size() < 2) return false;
        int n = series1.size();
        return series1.get(n-2) <= series2.get(n-2) && series1.get(n-1) > series2.get(n-1);
    }

    public boolean crossBelow(List<Double> series1, List<Double> series2) {
        if (series1.size() < 2 || series2.size() < 2) return false;
        int n = series1.size();
        return series1.get(n-2) >= series2.get(n-2) && series1.get(n-1) < series2.get(n-1);
    }

    // ── Composite Signal Score ────────────────────────────────────────────────
    public Map<String, Object> computeSignalSummary(List<Double> opens, List<Double> highs,
                                                     List<Double> lows, List<Double> closes,
                                                     List<Double> volumes) {
        Map<String, Object> signals = new LinkedHashMap<>();
        if (closes == null || closes.size() < 30) {
            signals.put("score", 0);
            signals.put("signal", "INSUFFICIENT_DATA");
            return signals;
        }
        int bullish = 0, bearish = 0;

        double rsiVal = rsi(closes, 14);
        signals.put("rsi_14", rsiVal);
        if (rsiVal > 50) bullish++; else bearish++;
        if (rsiVal > 70) bearish++;
        if (rsiVal < 30) bullish++;

        double ema20 = ema(closes, 20);
        double ema50 = ema(closes, 50);
        signals.put("ema_20", ema20);
        signals.put("ema_50", ema50);
        if (last(closes) > ema20) bullish++; else bearish++;
        if (ema20 > ema50) bullish++; else bearish++;

        double[] macdV = macd(closes, 12, 26, 9);
        signals.put("macd_histogram", macdV[2]);
        if (macdV[2] > 0) bullish++; else bearish++;

        if (highs != null && highs.size() >= 14 && lows != null) {
            double adxV = adx(highs, lows, closes, 14);
            signals.put("adx_14", adxV);
            if (adxV > 25) bullish++;
        }

        double sma200 = sma(closes, Math.min(200, closes.size()));
        signals.put("sma_200", sma200);
        if (last(closes) > sma200) bullish++; else bearish++;

        if (volumes != null && volumes.size() >= 20) {
            double volRatio = volumeSpikeRatio(volumes, 20);
            signals.put("volume_spike_ratio", volRatio);
            if (volRatio > 1.5 && last(closes) > closes.get(closes.size()-2)) bullish++;
        }

        int total = bullish + bearish;
        double score = total > 0 ? round1((double) bullish / total * 100) : 50;
        signals.put("bullish_count", bullish);
        signals.put("bearish_count", bearish);
        signals.put("score", score);
        signals.put("signal", score >= 70 ? "STRONG_BUY" : score >= 55 ? "BUY"
            : score <= 30 ? "STRONG_SELL" : score <= 45 ? "SELL" : "NEUTRAL");
        return signals;
    }

    // ── Batch Compute All Indicators ──────────────────────────────────────────
    public Map<String, Object> computeAll(List<Double> opens, List<Double> highs,
                                           List<Double> lows, List<Double> closes,
                                           List<Double> volumes) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (closes == null || closes.isEmpty()) return r;

        r.put("sma_20", sma(closes, 20));
        r.put("sma_50", sma(closes, 50));
        r.put("sma_200", sma(closes, Math.min(200, closes.size())));
        r.put("ema_9", ema(closes, 9));
        r.put("ema_20", ema(closes, 20));
        r.put("ema_50", ema(closes, 50));
        r.put("ema_200", ema(closes, Math.min(200, closes.size())));
        r.put("wma_20", wma(closes, 20));
        if (highs != null && lows != null && volumes != null)
            r.put("vwap", vwap(highs, lows, closes, volumes));

        r.put("rsi_14", rsi(closes, 14));
        r.put("rsi_zone", detectRsiZone(closes, 14));
        double[] mv = macd(closes, 12, 26, 9);
        r.put("macd", mv[0]); r.put("macd_signal", mv[1]); r.put("macd_histogram", mv[2]);
        r.put("macd_crossover", detectMacdSignal(closes));
        if (highs != null && lows != null) {
            double[] st = stochastic(highs, lows, closes, 14, 3);
            r.put("stoch_k", st[0]); r.put("stoch_d", st[1]);
        }
        double[] sri = stochasticRsi(closes, 14, 14);
        r.put("stoch_rsi_k", sri[0]); r.put("stoch_rsi_d", sri[1]);
        r.put("roc_14", roc(closes, 14));
        r.put("momentum_14", momentum(closes, 14));
        if (highs != null && lows != null) {
            r.put("williams_r", williamsR(highs, lows, closes, 14));
            r.put("cci_20", cci(highs, lows, closes, 20));
        }

        if (highs != null && lows != null) {
            double[] dm = dmi(highs, lows, closes, 14);
            r.put("plus_di", dm[0]); r.put("minus_di", dm[1]); r.put("adx_14", dm[2]);
            double[] stt = supertrend(highs, lows, closes, 7, 3.0);
            r.put("supertrend", stt[0]); r.put("supertrend_direction", stt[1] > 0 ? "BULLISH" : "BEARISH");
            double[] ps = parabolicSar(highs, lows, closes, 0.02, 0.02, 0.2);
            r.put("parabolic_sar", ps[0]); r.put("psar_direction", ps[1] > 0 ? "BULLISH" : "BEARISH");
            double[] ich = ichimoku(highs, lows, closes, 9, 26, 52);
            r.put("ichimoku_tenkan", ich[0]); r.put("ichimoku_kijun", ich[1]);
            r.put("ichimoku_senkou_a", ich[2]); r.put("ichimoku_senkou_b", ich[3]);
            r.put("ichimoku_chikou", ich[4]);
        }
        r.put("ema_crossover_9_21", detectEmaCrossover(closes, 9, 21));
        r.put("ema_crossover_20_50", detectEmaCrossover(closes, 20, 50));

        if (highs != null && lows != null) {
            r.put("atr_14", atr(highs, lows, closes, 14));
            double[] kc = keltnerChannel(highs, lows, closes, 20, 10, 1.5);
            r.put("keltner_upper", kc[0]); r.put("keltner_mid", kc[1]); r.put("keltner_lower", kc[2]);
            double[] dc = donchianChannel(highs, lows, 20);
            r.put("donchian_high", dc[0]); r.put("donchian_mid", dc[1]); r.put("donchian_low", dc[2]);
        }
        double[] bb = bollingerBands(closes, 20, 2.0);
        r.put("bb_upper", bb[0]); r.put("bb_mid", bb[1]); r.put("bb_lower", bb[2]);
        r.put("bb_width", bollingerBandWidth(closes, 20, 2.0));
        r.put("bb_percent_b", bollingerPercentB(closes, 20, 2.0));

        if (volumes != null && !volumes.isEmpty()) {
            r.put("obv", obv(closes, volumes));
            r.put("volume_change_pct", volumeChange(volumes));
            r.put("avg_volume_20", avgVolume(volumes, 20));
            r.put("volume_spike_ratio", volumeSpikeRatio(volumes, 20));
            r.put("volume_spike", isVolumeSpikeDetected(volumes, 20, 2.0));
            if (highs != null && lows != null)
                r.put("mfi_14", mfi(highs, lows, closes, volumes, 14));
        }

        if (highs != null && lows != null) {
            double pH = highs.size() >= 2 ? highs.get(highs.size()-2) : last(highs);
            double pL = lows.size() >= 2 ? lows.get(lows.size()-2) : last(lows);
            double pC = closes.size() >= 2 ? closes.get(closes.size()-2) : last(closes);
            double[] pp = pivotPoints(pH, pL, pC);
            r.put("pivot", pp[0]); r.put("r1", pp[1]); r.put("r2", pp[2]); r.put("r3", pp[3]);
            r.put("s1", pp[4]); r.put("s2", pp[5]); r.put("s3", pp[6]);
            double[] cp = cpr(pH, pL, pC);
            r.put("cpr_pivot", cp[0]); r.put("cpr_tc", cp[1]); r.put("cpr_bc", cp[2]);
            r.put("cpr_width_pct", cprWidth(pH, pL, pC));
            double[] sr = supportResistance(highs, lows, closes, Math.min(50, closes.size()));
            r.put("resistance_1", sr[0]); r.put("resistance_2", sr[1]);
            r.put("support_1", sr[2]); r.put("support_2", sr[3]);
            double[] w52 = week52HighLow(highs, lows);
            r.put("week_52_high", w52[0]); r.put("week_52_low", w52[1]);
        }

        r.put("change_pct_1d", priceChangePercentage(closes, 1));
        r.put("change_pct_5d", priceChangePercentage(closes, Math.min(5, closes.size()-1)));
        r.put("change_pct_20d", priceChangePercentage(closes, Math.min(20, closes.size()-1)));
        if (opens != null && !opens.isEmpty())
            r.put("gap_pct", gapPercentage(opens, closes));

        if (opens != null && opens.size() >= 3 && highs != null && lows != null) {
            int n = opens.size();
            double o = opens.get(n-1), h = highs.get(n-1), l = lows.get(n-1), c = closes.get(n-1);
            double po = opens.get(n-2), pc = closes.get(n-2);
            r.put("pattern_hammer", isHammer(o, h, l, c));
            r.put("pattern_doji", isDoji(o, h, l, c));
            r.put("pattern_marubozu", isMarubozu(o, h, l, c));
            r.put("pattern_spinning_top", isSpinningTop(o, h, l, c));
            r.put("pattern_inverted_hammer", isInvertedHammer(o, h, l, c));
            r.put("pattern_shooting_star", isShootingStar(o, h, l, c));
            r.put("pattern_bullish_engulfing", isBullishEngulfing(po, pc, o, c));
            r.put("pattern_bearish_engulfing", isBearishEngulfing(po, pc, o, c));
            r.put("pattern_morning_star", isMorningStar(opens, highs, lows, closes));
            r.put("pattern_evening_star", isEveningStar(opens, highs, lows, closes));
            r.put("pattern_three_white_soldiers", isThreeWhiteSoldiers(opens, closes));
            r.put("pattern_three_black_crows", isThreeBlackCrows(opens, closes));
            r.put("pattern_higher_high", isHigherHigh(highs));
            r.put("pattern_higher_low", isHigherLow(lows));
            r.put("pattern_lower_high", isLowerHigh(highs));
            r.put("pattern_lower_low", isLowerLow(lows));
            r.put("pattern_breakout_20", isBreakout(closes, highs, 20));
            r.put("pattern_breakdown_20", isBreakdown(closes, lows, 20));
        }

        Map<String, Object> summary = computeSignalSummary(opens, highs, lows, closes, volumes);
        r.put("signal_score", summary.get("score"));
        r.put("signal", summary.get("signal"));
        r.put("bullish_count", summary.get("bullish_count"));
        r.put("bearish_count", summary.get("bearish_count"));

        return r;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private double avg(List<Double> list) {
        return list.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }
    private double last(List<Double> list) {
        return list.isEmpty() ? 0 : list.get(list.size()-1);
    }
    private double stdDev(List<Double> list, double mean) {
        double v = list.stream().mapToDouble(d -> Math.pow(d - mean, 2)).average().orElse(0);
        return Math.sqrt(v);
    }
    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
