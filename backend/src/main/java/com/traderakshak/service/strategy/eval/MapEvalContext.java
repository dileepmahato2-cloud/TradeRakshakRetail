package com.traderakshak.service.strategy.eval;

import com.traderakshak.service.strategy.ast.Operand;
import com.traderakshak.service.strategy.ast.Timeframe;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An {@link EvalContext} backed by plain maps, one per session.
 *
 * <p>Exists so the evaluator can be tested against hand-written sessions with no database, no
 * Spring context and no indicator engine - which is what makes it practical to have a test per
 * operator rather than one integration test that covers the happy path and nothing else.
 *
 * <p>It is also the shape the screening engine's real context will take: a slice of an
 * already-loaded window, with an index into it, so {@link #shift} is a pointer move rather than
 * a query.
 */
public class MapEvalContext implements EvalContext {

    /** One session's values, keyed the way the AST names them. */
    public static class Session {
        final Map<String, Double> values = new LinkedHashMap<>();
        final String date;

        public Session(String date) { this.date = date; }

        public Session put(String key, Double value) {
            values.put(key.toUpperCase(Locale.ROOT), value);
            return this;
        }
    }

    private final String symbol;
    private final Map<Timeframe, List<Session>> byTimeframe;
    private final Timeframe timeframe;
    private final int index;

    /** Sessions supplied on their own are daily, which is what a rule means when it says nothing. */
    public MapEvalContext(String symbol, List<Session> sessions, int index) {
        this(symbol, oneTimeframe(Timeframe.DAILY, sessions), Timeframe.DAILY, index);
    }

    public MapEvalContext(String symbol, Map<Timeframe, List<Session>> byTimeframe,
                          Timeframe timeframe, int index) {
        this.symbol = symbol;
        this.byTimeframe = byTimeframe;
        this.timeframe = timeframe;
        this.index = index;
    }

    /** Positioned at the most recent session. */
    public static MapEvalContext at(String symbol, List<Session> sessions) {
        return new MapEvalContext(symbol, sessions, sessions.size() - 1);
    }

    /**
     * Several bar widths at once, positioned at the most recent bar of {@code primary}.
     *
     * <p>What a multi-timeframe rule needs to be tested against: daily bars and hourly bars for
     * the same symbol, each with their own history, so a condition that mixes the two can be
     * checked rather than assumed.
     */
    public static MapEvalContext at(String symbol, Map<Timeframe, List<Session>> byTimeframe,
                                    Timeframe primary) {
        List<Session> s = byTimeframe.get(primary);
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("No " + primary + " sessions supplied.");
        }
        return new MapEvalContext(symbol, new EnumMap<>(byTimeframe), primary, s.size() - 1);
    }

    private static Map<Timeframe, List<Session>> oneTimeframe(Timeframe tf, List<Session> s) {
        Map<Timeframe, List<Session>> m = new EnumMap<>(Timeframe.class);
        m.put(tf, s);
        return m;
    }

    public static Session session(String date) { return new Session(date); }

    @Override
    public Double field(String name) { return lookup(name); }

    @Override
    public Double indicator(String name, Map<String, Object> params) {
        // Keyed as NAME(p1,p2) so the same indicator with two periods can both be present,
        // then falling back to the bare name for tests that do not care about parameters.
        if (params != null && !params.isEmpty()) {
            StringBuilder sb = new StringBuilder(name).append('(');
            boolean first = true;
            for (Object v : params.values()) {
                if (!first) sb.append(',');
                sb.append(v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                    ? String.valueOf(n.longValue()) : String.valueOf(v));
                first = false;
            }
            Double hit = lookup(sb.append(')').toString());
            if (hit != null) return hit;
        }
        return lookup(name);
    }

    @Override
    public Double fundamental(String metric, Operand.Period period, Operand.Basis basis, int offset) {
        Double hit = lookup(metric + ":" + period + ":" + basis + ":" + offset);
        return hit != null ? hit : lookup(metric);
    }

    @Override
    public EvalContext shift(int sessionsBack) {
        int i = index + sessionsBack;
        if (i < 0 || i >= sessions().size()) return null;
        return new MapEvalContext(symbol, byTimeframe, timeframe, i);
    }

    @Override
    public EvalContext on(Timeframe tf) {
        if (tf == null || tf == timeframe) return this;
        List<Session> s = byTimeframe.get(tf);
        if (s == null || s.isEmpty()) return null;
        return new MapEvalContext(symbol, byTimeframe, tf, s.size() - 1);
    }

    private List<Session> sessions() { return byTimeframe.get(timeframe); }

    @Override
    public List<Double> series(Operand over, int periods) {
        List<Double> out = new ArrayList<>(periods);
        // Oldest first, which is the order the quantifiers read.
        for (int back = periods - 1; back >= 0; back--) {
            EvalContext c = shift(-back);
            if (c == null) continue;
            out.add(resolveOn(c, over));
        }
        return out;
    }

    /** Minimal resolution, enough for the operand kinds a series is built from. */
    private Double resolveOn(EvalContext c, Operand o) {
        if (o instanceof Operand.Literal l) return l.value();
        if (o instanceof Operand.Field f) return c.field(f.name());
        if (o instanceof Operand.Indicator i) return c.indicator(i.name(), i.params());
        if (o instanceof Operand.Fundamental f) {
            return c.fundamental(f.metric(), f.period(), f.basis(), f.offset());
        }
        if (o instanceof Operand.Shifted sh) {
            EvalContext back = c.shift(-sh.barsBack());
            return back == null ? null : resolveOn(back, sh.of());
        }
        return null;
    }

    @Override
    public String date() { return sessions().get(index).date; }

    @Override
    public String symbol() { return symbol; }

    private Double lookup(String key) {
        return key == null ? null : sessions().get(index).values.get(key.toUpperCase(Locale.ROOT));
    }
}
