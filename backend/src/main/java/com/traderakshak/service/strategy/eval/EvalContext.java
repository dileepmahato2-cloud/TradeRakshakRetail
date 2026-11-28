package com.traderakshak.service.strategy.eval;

import com.traderakshak.service.strategy.ast.Operand;
import com.traderakshak.service.strategy.ast.Timeframe;

import java.util.List;
import java.util.Map;

/**
 * Everything the evaluator can see: one symbol, one date, and the window behind it.
 *
 * <p>An interface rather than a class so the evaluator can be unit-tested without a database,
 * and so the screening engine can back it with an in-memory slice of the candle window it loaded
 * once for the whole run rather than querying per symbol per date.
 *
 * <p>Every accessor returns {@code null} for "not available", never 0 or NaN. A zero close and a
 * missing close are different facts, and a rule engine that cannot tell them apart will report a
 * stock as having crashed to zero when the truth is that it did not trade.
 */
public interface EvalContext {

    /** A raw OHLCV column on the bar being evaluated. */
    Double field(String name);

    /** A computed indicator, from cache or computed on demand. */
    Double indicator(String name, Map<String, Object> params);

    /** A financial figure on the stated basis, as it was known on the evaluation date. */
    Double fundamental(String metric, Operand.Period period, Operand.Basis basis, int offset);

    /**
     * The same symbol, {@code sessions} trading sessions away (negative is earlier).
     *
     * <p>Returns {@code null} when the window does not reach - which is what makes
     * CROSSES_ABOVE on a stock's first day evaluate to UNKNOWN rather than throwing.
     */
    EvalContext shift(int sessions);

    /**
     * The same symbol and date read on a different bar width, or {@code null} when no candle of
     * that width is stored.
     *
     * <p>Null rather than an empty context, so a 5-minute condition against hourly-only data
     * evaluates to UNKNOWN with a reason the user can act on. The alternative - quietly falling
     * back to daily - produces a screener that answers a question nobody asked.
     */
    EvalContext on(Timeframe timeframe);

    /**
     * The last {@code periods} values of an operand, oldest first, for the quantifiers.
     *
     * <p>Shorter than requested is allowed and is the caller's problem to judge; null entries mean
     * that period could not be measured.
     */
    List<Double> series(Operand over, int periods);

    /** The date being evaluated, for diagnostics and signal records. */
    String date();

    /** The symbol being evaluated. */
    String symbol();
}
