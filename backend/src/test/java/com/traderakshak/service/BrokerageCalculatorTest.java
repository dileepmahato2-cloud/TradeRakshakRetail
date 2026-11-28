package com.traderakshak.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Charges at Dhan's published rates.
 *
 * <p>These numbers decide whether a strategy reads as profitable, so they are pinned against
 * hand-computed figures rather than against whatever the code currently returns. The risk being
 * guarded is not a crash - a wrong rate produces a plausible number, and a one-session strategy
 * whose true cost is 0.30% a round trip looks viable at 0.20% and is not.
 *
 * <p>The mock returns whichever default the calculator asks for, so every assertion below exercises
 * the published rates in {@code BrokerageCalculator.DEFAULTS}. A rate changing there without these
 * expectations changing will fail.
 */
class BrokerageCalculatorTest {

    private BrokerageCalculator calc;
    private AppConfigService config;

    @BeforeEach
    void setUp() {
        config = mock(AppConfigService.class);
        when(config.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        calc = new BrokerageCalculator(config);
    }

    // ---------------------------------------------------------------- delivery

    @Test
    void deliveryBuyLegChargesNoBrokerageAndStampDuty() {
        var c = calc.charges(BrokerageCalculator.Product.DELIVERY,
                             BrokerageCalculator.Side.BUY, "NSE", 200, 100, false);

        assertEquals(20_000.0, c.turnover());
        assertEquals(0.0,  c.brokerage(),          "equity delivery is zero-brokerage at Dhan");
        assertEquals(0.61, c.transactionCharges(), "NSE 0.0030699% of 20,000");
        assertEquals(20.0, c.stt(),                "delivery STT is 0.1%, charged on the buy leg too");
        assertEquals(0.02, c.sebiTurnoverFee());
        assertEquals(3.0,  c.stampDuty(),          "stamp duty 0.015% on a delivery buy");
        assertEquals(0.11, c.gst(),                "18% of brokerage + txn + SEBI + IPFT only");
        assertEquals(0.0,  c.dpCharges(),          "the DP charge is a sell-side charge");
        assertEquals(23.74, c.total());
    }

    @Test
    void deliverySellLegChargesTheDpFeeAndNoStampDuty() {
        var c = calc.charges(BrokerageCalculator.Product.DELIVERY,
                             BrokerageCalculator.Side.SELL, "NSE", 200, 100, true);

        assertEquals(0.0,   c.stampDuty(), "stamp duty is buy-side only");
        assertEquals(20.0,  c.stt(),       "delivery STT applies to the sell leg as well");
        assertEquals(12.50, c.dpCharges());
        // GST on the service components (0.11) plus GST on the DP charge (2.25).
        assertEquals(2.36,  c.gst());
        assertEquals(35.49, c.total());
    }

    @Test
    void dpChargeIsLeviedOncePerIsinPerDayNotPerOrder() {
        var withDp = calc.charges(BrokerageCalculator.Product.DELIVERY,
                                  BrokerageCalculator.Side.SELL, "NSE", 200, 100, true);
        var without = calc.charges(BrokerageCalculator.Product.DELIVERY,
                                   BrokerageCalculator.Side.SELL, "NSE", 200, 100, false);

        assertEquals(12.50, withDp.dpCharges());
        assertEquals(0.0,   without.dpCharges(),
            "an exit split across several orders must not be charged the DP fee more than once");
        assertEquals(14.75, withDp.total() - without.total(), 0.001, "Rs 12.50 plus 18% GST");
    }

    @Test
    void deliveryRoundTripCostsAboutThirtyBasisPoints() {
        var rt = calc.roundTrip(BrokerageCalculator.Product.DELIVERY, "NSE", 200, 200, 100);

        // 23.74 on the buy, 35.49 on the sell. Each leg rounds on its own because a contract note
        // charges per order - summing unrounded legs and rounding once gives 59.25 and would not
        // reconcile.
        assertEquals(59.23, rt.total());
        assertEquals(0.30,  rt.totalPct(), 0.005,
            "this is the hurdle a one-session close-to-close hold has to clear before it earns anything");
        assertEquals(40.0,  rt.stt(), "STT is the largest single component of a delivery round trip");
    }

    // ---------------------------------------------------------------- intraday

    @Test
    void intradaySttIsSellSideOnlyAndAQuarterOfTheDeliveryRate() {
        var buy  = calc.charges(BrokerageCalculator.Product.INTRADAY,
                                BrokerageCalculator.Side.BUY, "NSE", 200, 100, false);
        var sell = calc.charges(BrokerageCalculator.Product.INTRADAY,
                                BrokerageCalculator.Side.SELL, "NSE", 200, 100, true);

        assertEquals(0.0, buy.stt(),  "intraday STT is not charged on the buy leg");
        assertEquals(5.0, sell.stt(), "0.025% of 20,000");
        assertEquals(0.0, sell.dpCharges(), "nothing is delivered, so there is no DP charge");
        assertEquals(1.0, buy.stampDuty(), "intraday stamp duty is 0.003%, a fifth of delivery's");
    }

    @Test
    void intradayBrokerageIsACapNotAFlatFee() {
        // Rs 20 "or 0.03% of trade value, whichever is lower". Reading it as a flat Rs 20 would
        // overcharge a small order by nearly Rs 18.50 and make small positions look uneconomic.
        var small = calc.charges(BrokerageCalculator.Product.INTRADAY,
                                 BrokerageCalculator.Side.BUY, "NSE", 100, 50, false);   // Rs 5,000
        assertEquals(1.50, small.brokerage(), "0.03% of 5,000, which is below the Rs 20 cap");

        var large = calc.charges(BrokerageCalculator.Product.INTRADAY,
                                 BrokerageCalculator.Side.BUY, "NSE", 200, 5_000, false); // Rs 10,00,000
        assertEquals(20.0, large.brokerage(), "0.03% would be Rs 300, so the cap binds");
    }

    // ---------------------------------------------------------------- details

    @Test
    void bseCarriesAHigherTransactionChargeThanNse() {
        var nse = calc.charges(BrokerageCalculator.Product.DELIVERY,
                               BrokerageCalculator.Side.BUY, "NSE", 200, 100, false);
        var bse = calc.charges(BrokerageCalculator.Product.DELIVERY,
                               BrokerageCalculator.Side.BUY, "BSE", 200, 100, false);

        assertEquals(0.61, nse.transactionCharges());
        assertEquals(0.75, bse.transactionCharges());
        assertTrue(bse.total() > nse.total());
    }

    @Test
    void anUnknownExchangeFallsBackToTheNseRateNotToZero() {
        var unknown = calc.charges(BrokerageCalculator.Product.DELIVERY,
                                   BrokerageCalculator.Side.BUY, "MCX", 200, 100, false);
        assertEquals(0.61, unknown.transactionCharges(),
            "a zero default would make an unrecognised exchange look cheaper than a known one");
    }

    @Test
    void gstIsNotChargedOnSttOrStampDuty() {
        var c = calc.charges(BrokerageCalculator.Product.DELIVERY,
                             BrokerageCalculator.Side.BUY, "NSE", 200, 100, false);
        // STT 20 + stamp 3 = 23 of the 23.74 total. Had GST been charged on those too it would be
        // 0.11 + 4.14 = 4.25 rather than 0.11 - a 17% overstatement of the whole buy leg.
        assertEquals(0.11, c.gst(), "GST applies to services, not to taxes");
    }

    @Test
    void sttAndStampDutyRoundToWholeRupeesEverythingElseToPaise() {
        // Dhan: "Stamp Duty and STT are rounded off to the nearest rupee and all other charges to
        // the nearest 2 decimals." Turnover 20,555 makes every component land off a round number.
        var c = calc.charges(BrokerageCalculator.Product.DELIVERY,
                             BrokerageCalculator.Side.BUY, "NSE", 4111, 5, false);

        assertEquals(20_555.0, c.turnover());
        assertEquals(21.0, c.stt(),       "0.1% of 20,555 is 20.555, rounded to the nearest rupee");
        assertEquals(3.0,  c.stampDuty(), "0.015% of 20,555 is 3.08, rounded to the nearest rupee");
        assertEquals(c.transactionCharges(), Math.round(c.transactionCharges() * 100.0) / 100.0,
            "every other component keeps paise");
    }

    @Test
    void aZeroOrNegativePositionCostsNothingRatherThanThrowing() {
        assertEquals(0.0, calc.charges(BrokerageCalculator.Product.DELIVERY,
                                       BrokerageCalculator.Side.BUY, "NSE", 0, 100, true).total());
        assertEquals(0.0, calc.charges(BrokerageCalculator.Product.DELIVERY,
                                       BrokerageCalculator.Side.BUY, "NSE", 200, 0, true).total());
        assertEquals(0.0, calc.charges(BrokerageCalculator.Product.DELIVERY,
                                       BrokerageCalculator.Side.BUY, "NSE", -5, 100, true).total());
    }

    @Test
    void theFlatDpChargeHurtsSmallPositionsHardest() {
        // Why position sizing matters to this strategy: the DP fee is Rs 12.50 whatever the size,
        // so it is a different drag at Rs 2,000 than at Rs 20,000. Capital allocation that splits
        // a small total across five stocks runs into this.
        var small = calc.roundTrip(BrokerageCalculator.Product.DELIVERY, "NSE", 200, 200, 10);
        var large = calc.roundTrip(BrokerageCalculator.Product.DELIVERY, "NSE", 200, 200, 100);

        assertEquals(2_000.0,  small.positionValue(), "capital committed, not both legs' turnover");
        assertEquals(4_000.0,  small.turnover(),      "both legs are taxed, so the tax base is the sum");
        assertEquals(20_000.0, large.positionValue());
        assertTrue(small.totalPct() > large.totalPct() * 2,
            "a Rs 2,000 round trip pays more than twice the percentage a Rs 20,000 one pays");
    }

    @Test
    void aChangedRateInApplicationSettingsTakesEffectWithoutARebuild() {
        // The rates are regulated and change; they are read per call for this reason.
        when(config.getDouble("charges.stt.delivery.pct", 0.1)).thenReturn(0.2);
        var c = calc.charges(BrokerageCalculator.Product.DELIVERY,
                             BrokerageCalculator.Side.BUY, "NSE", 200, 100, false);
        assertEquals(40.0, c.stt(), "0.2% of 20,000");
    }
}
