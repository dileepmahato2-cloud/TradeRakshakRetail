package com.traderakshak;

import com.traderakshak.service.TechnicalIndicatorEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TechnicalIndicatorEngineTest {

    private TechnicalIndicatorEngine engine;
    private List<Double> closes;
    private List<Double> highs;
    private List<Double> lows;
    private List<Double> opens;
    private List<Double> volumes;

    @BeforeEach
    void setUp() {
        engine = new TechnicalIndicatorEngine();
        closes  = List.of(100.0, 102.0, 101.0, 103.0, 105.0, 104.0, 106.0, 108.0, 107.0, 109.0,
                          110.0, 108.0, 111.0, 113.0, 112.0, 114.0, 115.0, 113.0, 116.0, 118.0,
                          117.0, 119.0, 120.0, 118.0, 121.0, 123.0, 122.0, 124.0, 126.0, 125.0);
        highs   = closes.stream().map(c -> c + 2).toList();
        lows    = closes.stream().map(c -> c - 2).toList();
        opens   = closes.stream().map(c -> c - 0.5).toList();
        volumes = List.of(1000.0, 1200.0, 900.0, 1500.0, 1100.0, 1300.0, 800.0, 1400.0, 1000.0, 1600.0,
                          1200.0, 900.0, 1500.0, 1100.0, 1300.0, 800.0, 1400.0, 1000.0, 1600.0, 1200.0,
                          900.0, 1500.0, 1100.0, 1300.0, 800.0, 1400.0, 1000.0, 1600.0, 1200.0, 900.0);
    }

    // ── SMA ────────────────────────────────────────────────────────────────────

    @Test void sma_basic() {
        double result = engine.sma(List.of(10.0, 20.0, 30.0, 40.0, 50.0), 3);
        assertEquals(40.0, result, 0.01);
    }

    @Test void sma_insufficient_data() {
        double result = engine.sma(List.of(10.0), 5);
        assertEquals(10.0, result, 0.01);
    }

    @Test void sma_null_input() {
        assertEquals(0, engine.sma(null, 5));
    }

    @Test void smaSeries_length() {
        List<Double> series = engine.smaSeries(closes, 5);
        assertEquals(closes.size() - 4, series.size());
    }

    // ── EMA ────────────────────────────────────────────────────────────────────

    @Test void ema_basic() {
        double result = engine.ema(closes, 10);
        assertTrue(result > 0);
    }

    @Test void ema_insufficient_data() {
        double result = engine.ema(List.of(10.0, 20.0), 5);
        assertEquals(20.0, result, 0.01);
    }

    @Test void emaSeries_length() {
        List<Double> series = engine.emaSeries(closes, 10);
        assertEquals(closes.size() - 10 + 1, series.size());
    }

    // ── WMA ────────────────────────────────────────────────────────────────────

    @Test void wma_basic() {
        double result = engine.wma(List.of(10.0, 20.0, 30.0), 3);
        double expected = (10 * 1 + 20 * 2 + 30 * 3) / 6.0;
        assertEquals(expected, result, 0.01);
    }

    // ── RSI ────────────────────────────────────────────────────────────────────

    @Test void rsi_uptrend() {
        List<Double> uptrend = new ArrayList<>();
        for (int i = 0; i < 20; i++) uptrend.add(100.0 + i * 2);
        double rsi = engine.rsi(uptrend, 14);
        assertTrue(rsi > 70, "RSI should be > 70 in uptrend, got " + rsi);
    }

    @Test void rsi_downtrend() {
        List<Double> downtrend = new ArrayList<>();
        for (int i = 0; i < 20; i++) downtrend.add(200.0 - i * 2);
        double rsi = engine.rsi(downtrend, 14);
        assertTrue(rsi < 30, "RSI should be < 30 in downtrend, got " + rsi);
    }

    @Test void rsi_insufficient_data() {
        assertEquals(50.0, engine.rsi(List.of(10.0), 14));
    }

    @Test void rsiSeries_length() {
        List<Double> series = engine.rsiSeries(closes, 14);
        assertEquals(closes.size() - 14, series.size());
    }

    // ── MACD ──────────────────────────────────────────────────────────────────

    @Test void macd_returns_three_values() {
        double[] result = engine.macd(closes, 12, 26, 9);
        assertEquals(3, result.length);
    }

    @Test void macd_insufficient_data() {
        double[] result = engine.macd(List.of(1.0, 2.0), 12, 26, 9);
        assertArrayEquals(new double[]{0, 0, 0}, result);
    }

    @Test void macdSeries_needs_enough_data() {
        List<double[]> series = engine.macdSeries(closes, 12, 26, 9);
        // 30 points is barely enough for MACD(12,26,9) — series may be empty
        assertTrue(series.isEmpty() || series.get(0).length == 3);
    }

    // ── Bollinger Bands ───────────────────────────────────────────────────────

    @Test void bollingerBands_structure() {
        double[] bb = engine.bollingerBands(closes, 20, 2.0);
        assertEquals(3, bb.length);
        assertTrue(bb[0] >= bb[1], "Upper >= Mid");
        assertTrue(bb[1] >= bb[2], "Mid >= Lower");
    }

    @Test void bollingerBandWidth_positive() {
        double width = engine.bollingerBandWidth(closes, 20, 2.0);
        assertTrue(width >= 0);
    }

    @Test void bollingerPercentB_range() {
        double b = engine.bollingerPercentB(closes, 20, 2.0);
        assertTrue(b >= -50 && b <= 150);
    }

    // ── ATR ────────────────────────────────────────────────────────────────────

    @Test void atr_positive() {
        double result = engine.atr(highs, lows, closes, 14);
        assertTrue(result > 0, "ATR should be positive");
    }

    @Test void atrSeries_length() {
        List<Double> series = engine.atrSeries(highs, lows, closes, 14);
        assertTrue(series.size() > 0);
    }

    // ── ADX / DMI ─────────────────────────────────────────────────────────────

    @Test void adx_range() {
        double adx = engine.adx(highs, lows, closes, 14);
        assertTrue(adx >= 0 && adx <= 100, "ADX in 0-100, got " + adx);
    }

    @Test void dmi_returns_three_values() {
        double[] dmi = engine.dmi(highs, lows, closes, 14);
        assertEquals(3, dmi.length);
        assertTrue(dmi[0] >= 0); // +DI
        assertTrue(dmi[1] >= 0); // -DI
    }

    // ── Supertrend ────────────────────────────────────────────────────────────

    @Test void supertrend_returns_value_and_direction() {
        double[] st = engine.supertrend(highs, lows, closes, 7, 3.0);
        assertEquals(2, st.length);
        assertTrue(st[1] == 1 || st[1] == -1);
    }

    @Test void supertrendBullish_uptrend() {
        assertTrue(engine.supertrendBullish(highs, lows, closes, 7, 3.0));
    }

    // ── Stochastic ────────────────────────────────────────────────────────────

    @Test void stochastic_range() {
        double[] stoch = engine.stochastic(highs, lows, closes, 14, 3);
        assertTrue(stoch[0] >= 0 && stoch[0] <= 100, "%K in range");
        assertTrue(stoch[1] >= 0 && stoch[1] <= 100, "%D in range");
    }

    @Test void stochasticRsi_range() {
        double[] sr = engine.stochasticRsi(closes, 14, 14);
        assertTrue(sr[0] >= 0 && sr[0] <= 100);
    }

    // ── ROC / Momentum ───────────────────────────────────────────────────────

    @Test void roc_positive_uptrend() {
        double roc = engine.roc(closes, 10);
        assertTrue(roc > 0);
    }

    @Test void momentum_positive_uptrend() {
        double m = engine.momentum(closes, 10);
        assertTrue(m > 0);
    }

    // ── Williams %R ───────────────────────────────────────────────────────────

    @Test void williamsR_range() {
        double wr = engine.williamsR(highs, lows, closes, 14);
        assertTrue(wr >= -100 && wr <= 0, "Williams %R in -100 to 0, got " + wr);
    }

    // ── CCI ───────────────────────────────────────────────────────────────────

    @Test void cci_returns_value() {
        double cci = engine.cci(highs, lows, closes, 20);
        assertTrue(cci != 0 || closes.size() < 20);
    }

    // ── VWAP ──────────────────────────────────────────────────────────────────

    @Test void vwap_positive() {
        double vwap = engine.vwap(highs, lows, closes, volumes);
        assertTrue(vwap > 0);
    }

    // ── Parabolic SAR ─────────────────────────────────────────────────────────

    @Test void parabolicSar_returns_value() {
        double[] sar = engine.parabolicSar(highs, lows, closes, 0.02, 0.02, 0.2);
        assertEquals(2, sar.length);
        assertTrue(sar[0] > 0);
        assertTrue(sar[1] == 1 || sar[1] == -1);
    }

    // ── Ichimoku ──────────────────────────────────────────────────────────────

    @Test void ichimoku_returns_five_values() {
        double[] ich = engine.ichimoku(highs, lows, closes, 9, 26, 26);
        assertEquals(5, ich.length);
        assertTrue(ich[0] > 0); // tenkan
        assertTrue(ich[1] > 0); // kijun
    }

    // ── Keltner Channel ───────────────────────────────────────────────────────

    @Test void keltnerChannel_ordered() {
        double[] kc = engine.keltnerChannel(highs, lows, closes, 20, 10, 1.5);
        assertEquals(3, kc.length);
        assertTrue(kc[0] >= kc[1]);
        assertTrue(kc[1] >= kc[2]);
    }

    // ── Donchian Channel ──────────────────────────────────────────────────────

    @Test void donchianChannel_ordered() {
        double[] dc = engine.donchianChannel(highs, lows, 20);
        assertEquals(3, dc.length);
        assertTrue(dc[0] >= dc[1]);
        assertTrue(dc[1] >= dc[2]);
    }

    // ── OBV ───────────────────────────────────────────────────────────────────

    @Test void obv_not_zero() {
        double obv = engine.obv(closes, volumes);
        assertTrue(obv != 0);
    }

    @Test void obvSeries_length() {
        List<Double> series = engine.obvSeries(closes, volumes);
        assertEquals(closes.size(), series.size());
    }

    // ── MFI ───────────────────────────────────────────────────────────────────

    @Test void mfi_range() {
        double mfi = engine.mfi(highs, lows, closes, volumes, 14);
        assertTrue(mfi >= 0 && mfi <= 100, "MFI in 0-100, got " + mfi);
    }

    // ── Volume helpers ────────────────────────────────────────────────────────

    @Test void volumeChange_calculation() {
        double vc = engine.volumeChange(List.of(1000.0, 1200.0));
        assertEquals(20.0, vc, 0.01);
    }

    @Test void avgVolume_equals_sma() {
        double avg = engine.avgVolume(volumes, 10);
        double sma = engine.sma(volumes, 10);
        assertEquals(sma, avg, 0.01);
    }

    @Test void volumeSpike_detected() {
        List<Double> vols = new ArrayList<>(Collections.nCopies(20, 1000.0));
        vols.add(5000.0);
        assertTrue(engine.isVolumeSpikeDetected(vols, 20, 2.0));
    }

    @Test void volumeSpike_not_detected() {
        List<Double> vols = new ArrayList<>(Collections.nCopies(20, 1000.0));
        vols.add(1100.0);
        assertFalse(engine.isVolumeSpikeDetected(vols, 20, 2.0));
    }

    // ── Pivot Points ──────────────────────────────────────────────────────────

    @Test void pivotPoints_structure() {
        double[] pp = engine.pivotPoints(110, 90, 100);
        assertEquals(7, pp.length);
        assertEquals(100.0, pp[0], 0.01); // pivot = (110+90+100)/3 = 100
        assertTrue(pp[1] > pp[0]); // R1 > pivot
        assertTrue(pp[4] < pp[0]); // S1 < pivot
    }

    @Test void fibonacciPivots_structure() {
        double[] fp = engine.fibonacciPivotPoints(110, 90, 100);
        assertEquals(7, fp.length);
    }

    // ── CPR ────────────────────────────────────────────────────────────────────

    @Test void cpr_structure() {
        double[] c = engine.cpr(110, 90, 100);
        assertEquals(3, c.length); // pivot, tc, bc
    }

    @Test void cprWidth_positive() {
        double w = engine.cprWidth(110, 90, 100);
        assertTrue(w >= 0);
    }

    // ── Support/Resistance ────────────────────────────────────────────────────

    @Test void supportResistance_structure() {
        double[] sr = engine.supportResistance(highs, lows, closes, 20);
        assertEquals(4, sr.length);
        assertTrue(sr[0] >= sr[1]); // r1 >= r2
        assertTrue(sr[2] <= sr[3]); // s1 <= s2
    }

    // ── 52-Week High/Low ──────────────────────────────────────────────────────

    @Test void week52HighLow_structure() {
        double[] w52 = engine.week52HighLow(highs, lows);
        assertEquals(2, w52.length);
        assertTrue(w52[0] >= w52[1]);
    }

    // ── Gap / Return / Price Change ───────────────────────────────────────────

    @Test void gapPercentage_basic() {
        double gap = engine.gapPercentage(List.of(100.0, 105.0), List.of(100.0, 102.0));
        assertEquals(5.0, gap, 0.01);
    }

    @Test void returnPercentage_basic() {
        assertEquals(10.0, engine.returnPercentage(100, 110), 0.01);
        assertEquals(-10.0, engine.returnPercentage(100, 90), 0.01);
    }

    @Test void priceChangePercentage_basic() {
        double pct = engine.priceChangePercentage(List.of(100.0, 110.0), 1);
        assertEquals(10.0, pct, 0.01);
    }

    // ── Pattern Detection ─────────────────────────────────────────────────────

    @Test void higherHigh_detected() {
        assertTrue(engine.isHigherHigh(List.of(10.0, 12.0, 14.0)));
        assertFalse(engine.isHigherHigh(List.of(14.0, 12.0, 10.0)));
    }

    @Test void lowerLow_detected() {
        assertTrue(engine.isLowerLow(List.of(14.0, 12.0, 10.0)));
        assertFalse(engine.isLowerLow(List.of(10.0, 12.0, 14.0)));
    }

    @Test void breakout_detected() {
        // last close 110 exceeds max high in previous 5 bars (highs max = 103)
        List<Double> c = new ArrayList<>(List.of(100.0, 101.0, 102.0, 100.0, 99.0, 98.0, 100.0, 110.0));
        List<Double> h = c.stream().map(v -> v + 1).toList();
        assertTrue(engine.isBreakout(c, h, 5));
    }

    // ── Candlestick Patterns ──────────────────────────────────────────────────

    @Test void hammer_detected() {
        // body=1, lower shadow=9, upper shadow=0 → classic hammer
        assertTrue(engine.isHammer(100, 101, 91, 101));
    }

    @Test void doji_detected() {
        assertTrue(engine.isDoji(100, 105, 95, 100.2));
    }

    @Test void marubozu_detected() {
        assertTrue(engine.isMarubozu(100, 110.5, 99.5, 110));
    }

    @Test void spinningTop_detected() {
        assertTrue(engine.isSpinningTop(100, 108, 92, 101));
    }

    @Test void bullishEngulfing_detected() {
        assertTrue(engine.isBullishEngulfing(105, 100, 99, 106));
    }

    @Test void bearishEngulfing_detected() {
        assertTrue(engine.isBearishEngulfing(100, 105, 106, 99));
    }

    @Test void threeWhiteSoldiers_detected() {
        assertTrue(engine.isThreeWhiteSoldiers(
            List.of(100.0, 102.0, 104.0), List.of(103.0, 105.0, 107.0)));
    }

    @Test void threeBlackCrows_detected() {
        assertTrue(engine.isThreeBlackCrows(
            List.of(107.0, 105.0, 103.0), List.of(104.0, 102.0, 100.0)));
    }

    // ── Crossover Detection ───────────────────────────────────────────────────

    @Test void crossAbove_detected() {
        assertTrue(engine.crossAbove(List.of(9.0, 11.0), List.of(10.0, 10.0)));
        assertFalse(engine.crossAbove(List.of(11.0, 9.0), List.of(10.0, 10.0)));
    }

    @Test void crossBelow_detected() {
        assertTrue(engine.crossBelow(List.of(11.0, 9.0), List.of(10.0, 10.0)));
        assertFalse(engine.crossBelow(List.of(9.0, 11.0), List.of(10.0, 10.0)));
    }

    // ── Signal Detection ──────────────────────────────────────────────────────

    @Test void detectRsiZone_values() {
        List<Double> uptrend = new ArrayList<>();
        for (int i = 0; i < 20; i++) uptrend.add(100.0 + i * 3);
        String zone = engine.detectRsiZone(uptrend, 14);
        assertTrue(Set.of("OVERBOUGHT", "BULLISH", "NEUTRAL", "BEARISH", "OVERSOLD").contains(zone));
    }

    @Test void detectMacdSignal_returns_string() {
        String signal = engine.detectMacdSignal(closes);
        assertNotNull(signal);
        assertTrue(Set.of("BULLISH_CROSSOVER", "BEARISH_CROSSOVER", "BULLISH", "BEARISH", "NEUTRAL").contains(signal));
    }

    @Test void detectEmaCrossover_returns_string() {
        String signal = engine.detectEmaCrossover(closes, 9, 21);
        assertNotNull(signal);
        assertTrue(Set.of("GOLDEN_CROSS", "DEATH_CROSS", "BULLISH", "BEARISH", "NEUTRAL").contains(signal));
    }

    // ── Composite / Batch ─────────────────────────────────────────────────────

    @Test void computeSignalSummary_structure() {
        Map<String, Object> summary = engine.computeSignalSummary(opens, highs, lows, closes, volumes);
        assertTrue(summary.containsKey("score"));
        assertTrue(summary.containsKey("signal"));
        assertTrue(summary.containsKey("bullish_count"));
        assertTrue(summary.containsKey("bearish_count"));
    }

    @Test void computeAll_structure() {
        Map<String, Object> all = engine.computeAll(opens, highs, lows, closes, volumes);
        assertTrue(all.size() > 50, "computeAll should return 50+ values, got " + all.size());
        assertTrue(all.containsKey("rsi_14"));
        assertTrue(all.containsKey("macd"));
        assertTrue(all.containsKey("ema_20"));
        assertTrue(all.containsKey("sma_200"));
        assertTrue(all.containsKey("atr_14"));
        assertTrue(all.containsKey("signal"));
        assertTrue(all.containsKey("pivot"));
        assertTrue(all.containsKey("obv"));
    }

    @Test void computeAll_handles_null_volumes() {
        Map<String, Object> result = engine.computeAll(opens, highs, lows, closes, null);
        assertTrue(result.containsKey("rsi_14"));
        assertFalse(result.containsKey("obv"));
    }

    @Test void computeAll_handles_empty_data() {
        Map<String, Object> result = engine.computeAll(null, null, null, List.of(), null);
        assertTrue(result.isEmpty());
    }
}
