package com.traderakshak.service.strategy.eval;

import com.traderakshak.service.strategy.ast.Operand;

import java.util.ArrayList;
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
    private final List<Session> sessions;
    private final int index;

    public MapEvalContext(String symbol, List<Session> sessions, int index) {
        this.symbol = symbol;
        this.sessions = sessions;
        this.index = index;
    }

    /** Positioned at the most recent session. */
    public static MapEvalContext at(String symbol, List<Session> sessions) {
        return new MapEvalContext(symbol, sessions, sessions.size() - 1);
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
        if (i < 0 || i >= sessions.size()) return null;
        return new MapEvalContext(symbol, sessions, i);
    }

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
        return null;
    }

    @Override
    public String date() { return sessions.get(index).date; }

    @Override
    public String symbol() { return symbol; }

    private Double lookup(String key) {
        return key == null ? null : sessions.get(index).values.get(key.toUpperCase(Locale.ROOT));
    }
}
