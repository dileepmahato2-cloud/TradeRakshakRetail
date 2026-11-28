package com.traderakshak.service.strategy.ast;

/**
 * What the stored candles actually support, asked of the data rather than assumed.
 *
 * <p>An interface, and not a method on {@link Timeframe}, for two reasons. The answer changes
 * when candles are collected, so it cannot be a constant; and the validator has to be testable
 * without a database, which {@link #ALL} makes possible.
 */
public interface TimeframeAvailability {

    /** True when a condition on this width can be measured from stored data. */
    boolean available(Timeframe tf);

    /** Why not, phrased for the person who picked it. */
    default String unavailableReason(Timeframe tf) {
        return "No " + tf.label() + " candles are stored, so a condition on this timeframe "
             + "cannot be measured and would match nothing.";
    }

    /** Everything available: for unit tests, and for callers that only check rule shape. */
    TimeframeAvailability ALL = tf -> true;
}
