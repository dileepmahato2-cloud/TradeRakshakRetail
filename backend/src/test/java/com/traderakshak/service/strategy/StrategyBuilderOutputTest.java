package com.traderakshak.service.strategy;

import com.traderakshak.service.strategy.ast.AstNode;
import com.traderakshak.service.strategy.ast.AstParser;
import com.traderakshak.service.strategy.ast.AstValidator;
import com.traderakshak.service.strategy.ast.FieldCatalogue;
import com.traderakshak.service.strategy.ast.TimeframeAvailability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Strategy Builder canvas actually sends, through the real parser and validator.
 *
 * <p>The JSON below is not a paraphrase: it is byte-for-byte the shape
 * {@code StrategyCanvasComponent.buildAst()} produces for the strategy as specified - two ALL
 * groups joined by AND, a weekly group and a daily one, with per-operand bar offsets for the
 * "previous session" and "one week ago" rows.
 *
 * <p>It is here rather than only in a browser because the builder, the validator and the
 * screener are three things that have already drifted apart once on this screen. A test that
 * pins the exact payload is the only thing that notices when one of them moves.
 */
class StrategyBuilderOutputTest {

    private AstParser parser;
    private AstValidator validator;

    @BeforeEach
    void setUp() {
        parser = new AstParser();
        // ALL rather than the live availability: this test is about the rule's shape and
        // vocabulary, not about which candle widths happen to be collected today. Weekly
        // availability has its own coverage in WeeklyTimeframeTest.
        validator = new AstValidator(new FieldCatalogue(), TimeframeAvailability.ALL);
    }

    /**
     * The strategy, exactly as the canvas serialises it.
     *
     * <pre>
     * Group 1 (ALL, weekly)   WEEKLY_RSI(14) &gt; 45
     *                         WEEKLY_RSI(14, 1 week ago) &lt;= 45
     *            AND
     * Group 2 (ALL, daily)    PREVIOUS_CLOSE &lt; BASE_PRICE
     *                         CURRENT_PRICE &gt;= BASE_PRICE
     *                         CURRENT_DAY_VOLUME &gt; AVG_VOLUME(20, previous completed sessions)
     *                         STOCHASTIC_%K(14,3,3) &gt; STOCHASTIC_%D(14,3,3)
     *                         previous session %K(14,3,3) &lt;= previous session %D(14,3,3)
     * </pre>
     *
     * <p>MARKET_CAP &gt;= 1000 CRORE is absent, and that is the honest state rather than an
     * omission: the column is empty for every NSE row, so the catalogue publishes it as NO_DATA
     * and the builder greys it out. A rule including it would match nothing, which on a screener
     * is indistinguishable from a market in which nothing qualifies.
     */
    private static final String CANVAS_JSON = """
        {
          "type": "logical", "op": "AND",
          "children": [
            {
              "type": "logical", "op": "AND",
              "children": [
                { "type": "compare", "op": "GT", "timeframe": "WEEKLY",
                  "left":  { "type": "indicator", "name": "RSI", "params": { "period": 14 } },
                  "right": { "type": "literal", "value": 45 } },
                { "type": "compare", "op": "LTE", "timeframe": "WEEKLY",
                  "left":  { "type": "indicator", "name": "RSI", "params": { "period": 14 },
                             "offset": 1 },
                  "right": { "type": "literal", "value": 45 } }
              ]
            },
            {
              "type": "logical", "op": "AND",
              "children": [
                { "type": "compare", "op": "LT", "timeframe": "DAILY",
                  "left":  { "type": "field", "name": "prev_close" },
                  "right": { "type": "field", "name": "base_price" } },
                { "type": "compare", "op": "GTE", "timeframe": "DAILY",
                  "left":  { "type": "field", "name": "close" },
                  "right": { "type": "field", "name": "base_price" } },
                { "type": "compare", "op": "GT", "timeframe": "DAILY",
                  "left":  { "type": "field", "name": "volume" },
                  "right": { "type": "indicator", "name": "avg_volume",
                             "params": { "period": 20 } } },
                { "type": "compare", "op": "GT", "timeframe": "DAILY",
                  "left":  { "type": "indicator", "name": "STOCH_K",
                             "params": { "kPeriod": 14, "smoothK": 3, "dPeriod": 3 } },
                  "right": { "type": "indicator", "name": "STOCH_D",
                             "params": { "kPeriod": 14, "smoothK": 3, "dPeriod": 3 } } },
                { "type": "compare", "op": "LTE", "timeframe": "DAILY",
                  "left":  { "type": "indicator", "name": "STOCH_K",
                             "params": { "kPeriod": 14, "smoothK": 3, "dPeriod": 3 },
                             "offset": 1 },
                  "right": { "type": "indicator", "name": "STOCH_D",
                             "params": { "kPeriod": 14, "smoothK": 3, "dPeriod": 3 },
                             "offset": 1 } }
              ]
            }
          ]
        }
        """;

    @Test
    void theCanvasPayloadParsesIntoTheExpectedTree() {
        AstNode root = parser.parse(CANVAS_JSON);

        AstNode.Logical top = assertInstanceOf(AstNode.Logical.class, root);
        assertEquals(AstNode.LogicalOp.AND, top.op());
        assertEquals(2, top.children().size(), "the two groups did not survive as two nodes");

        AstNode.Logical weekly = assertInstanceOf(AstNode.Logical.class, top.children().get(0));
        AstNode.Logical daily  = assertInstanceOf(AstNode.Logical.class, top.children().get(1));
        assertEquals(AstNode.LogicalOp.AND, weekly.op());
        assertEquals(AstNode.LogicalOp.AND, daily.op());
        assertEquals(2, weekly.children().size());
        assertEquals(5, daily.children().size());
    }

    @Test
    void everyConditionKeepsItsOwnTimeframe() {
        AstNode.Logical top = (AstNode.Logical) parser.parse(CANVAS_JSON);
        for (AstNode n : ((AstNode.Logical) top.children().get(0)).children()) {
            assertEquals("WEEKLY", ((AstNode.Compare) n).timeframe().name(),
                "a weekly condition lost its timeframe in parsing");
        }
        for (AstNode n : ((AstNode.Logical) top.children().get(1)).children()) {
            assertEquals("DAILY", ((AstNode.Compare) n).timeframe().name());
        }
    }

    @Test
    void theOffsetsBecomeShiftedOperands() {
        AstNode.Logical top = (AstNode.Logical) parser.parse(CANVAS_JSON);

        // "1 week ago" on the weekly group's second row.
        AstNode.Compare weekAgo = (AstNode.Compare) ((AstNode.Logical) top.children().get(0))
            .children().get(1);
        var shifted = assertInstanceOf(com.traderakshak.service.strategy.ast.Operand.Shifted.class,
            weekAgo.left(), "\"1 week ago\" did not become a shifted operand");
        assertEquals(1, shifted.barsBack());

        // "previous session" on BOTH sides of the daily group's last row.
        AstNode.Compare prevSession = (AstNode.Compare) ((AstNode.Logical) top.children().get(1))
            .children().get(4);
        assertInstanceOf(com.traderakshak.service.strategy.ast.Operand.Shifted.class,
            prevSession.left());
        assertInstanceOf(com.traderakshak.service.strategy.ast.Operand.Shifted.class,
            prevSession.right().get(0),
            "only one side of the previous-session comparison was shifted, so it compares "
          + "yesterday against today");
    }

    @Test
    void theWholeStrategyValidates() {
        AstValidator.Result r = validator.validate(parser.parse(CANVAS_JSON));
        List<AstValidator.Issue> errors = r.issues().stream()
            .filter(i -> i.severity() == AstValidator.Severity.ERROR).toList();
        assertTrue(errors.isEmpty(),
            "the exact payload the Strategy Builder sends was rejected: " + errors);
        assertTrue(r.ok(), "validation did not pass: " + r.issues());
    }

    /**
     * The two fields this strategy needed that the builder could not offer before.
     *
     * <p>base_price was readable by the screener and absent from the catalogue, so the crossing
     * every strategy on this platform is built from was the one rule the builder could not
     * express. smoothK is the middle number of (14,3,3): without it the builder could ask only
     * for the fast stochastic, which crosses far more often than the slow one it was meant to.
     */
    @Test
    void thePiecesThisStrategyNeedsArePublished() {
        FieldCatalogue c = new FieldCatalogue();

        FieldCatalogue.FieldDef base = c.get("base_price");
        assertTrue(base != null && base.availability() == FieldCatalogue.Availability.AVAILABLE,
            "base_price is not offered by the builder");

        FieldCatalogue.FieldDef k = c.get("STOCH_K");
        assertTrue(k != null && k.params().stream().anyMatch(p -> p.name().equals("smoothK")),
            "the stochastic does not expose its %K smoothing, so (14,3,3) cannot be asked for");

        FieldCatalogue.FieldDef mcap = c.get("market_cap");
        assertTrue(mcap != null, "market_cap is not in the catalogue at all, so the builder "
            + "cannot even say why the filter is unavailable");
        assertEquals(FieldCatalogue.Availability.NO_DATA, mcap.availability(),
            "market_cap is published as available while the column is empty for every NSE row, "
          + "which would offer a rule that silently matches nothing");
    }
}
