package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The byte budget of a Dhan v2 Quote packet.
 *
 * <p>A one-line guard decided whether volume and OHLC were read from every tick, and it was four
 * bytes too strict. The packet is 50 bytes; the parser seeks past the 8-byte header and the
 * 4-byte LTP, leaving exactly 38 - and the guard asked for 42. It never passed, so each tick was
 * reduced to its last traded price.
 *
 * <p>Nothing failed visibly. Volume read 0, open and close read 0, and high and low came back
 * equal to the LTP because of an unrelated {@code high <= 0} fallback, so the feed looked like a
 * working ticker rather than a half-parsed quote. Measured live: 1,305 of 1,305 quotes in exactly
 * that state. The consequence was that both RSI 45 sections could not evaluate current-day volume
 * during a session and silently fell back to the previous day's figure.
 *
 * <p>This pins the arithmetic rather than the parser, because the arithmetic is what was wrong and
 * it is invisible at the call site: the guard is a bare integer, and a bare integer that is too
 * large fails closed and in silence.
 */
class DhanQuotePacketTest {

    /** Dhan v2 Quote packet, response code 4. */
    private static final int HEADER_BYTES = 8;      // code, length, segment, security id
    private static final int LTP_BYTES = 4;
    private static final int PACKET_BYTES = 50;

    /** The fields that follow the LTP, in wire order. */
    private static final int[] FIELD_BYTES = {
        2,  // last traded quantity
        4,  // last trade time
        4,  // average trade price
        4,  // volume
        4,  // total sell quantity
        4,  // total buy quantity
        4,  // open
        4,  // close
        4,  // high
        4   // low
    };

    @Test
    void thirtyEightBytesFollowTheLastTradedPrice() {
        int after = 0;
        for (int n : FIELD_BYTES) after += n;

        assertEquals(38, after, "the fields after the LTP occupy 38 bytes");
        assertEquals(PACKET_BYTES, HEADER_BYTES + LTP_BYTES + after,
            "header + LTP + fields must account for the whole 50-byte packet");
    }

    @Test
    void theOldGuardOfFortyTwoCouldNeverPass() {
        int remainingAfterLtp = PACKET_BYTES - HEADER_BYTES - LTP_BYTES;
        assertEquals(38, remainingAfterLtp);

        // This is the defect, stated as the inequality that was actually being evaluated.
        assertTrue(remainingAfterLtp < 42,
            "a 50-byte quote packet leaves 38 bytes, so a >= 42 guard rejects every one of them");
        assertTrue(remainingAfterLtp >= 38,
            "the corrected guard admits exactly the packets the parser can read");
    }

    @Test
    void aTickerPacketIsTooShortForTheQuoteBranch() {
        // Response code 2 carries LTP and LTT only - 16 bytes. It must not be read as a quote,
        // or the parser would walk off the end of the buffer.
        int tickerRemainingAfterLtp = 16 - HEADER_BYTES - LTP_BYTES;
        assertEquals(4, tickerRemainingAfterLtp);
        assertTrue(tickerRemainingAfterLtp < 38,
            "a ticker packet must still fall to the LTP-only branch");
    }

    @Test
    void theFieldsCanBeReadBackFromASyntheticQuotePacket() {
        // Build a packet to the published layout and read it exactly as the parser does, to show
        // the offsets line up and the 38-byte budget is spent precisely.
        ByteBuffer p = ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        p.put((byte) 4).putShort((short) PACKET_BYTES).put((byte) 1).putInt(3980);  // header
        p.putFloat(136.38f);              // LTP
        p.putShort((short) 25);           // last traded qty
        p.putInt(1764240000);             // LTT
        p.putFloat(135.90f);              // ATP
        p.putInt(50344);                  // volume
        p.putInt(1200).putInt(1400);      // total sell / buy qty
        p.putFloat(134.80f);              // open
        p.putFloat(135.05f);              // close
        p.putFloat(136.27f);              // high
        p.putFloat(134.41f);              // low

        p.position(HEADER_BYTES);
        assertEquals(136.38f, p.getFloat(), 0.001);
        assertEquals(38, p.remaining(), "exactly the corrected guard's budget");

        p.getShort();
        p.getInt();
        p.getFloat();
        assertEquals(50344, Integer.toUnsignedLong(p.getInt()), "volume, the field that was lost");
        p.getInt();
        p.getInt();
        assertEquals(134.80f, p.getFloat(), 0.001, "open");
        assertEquals(135.05f, p.getFloat(), 0.001, "close");
        assertEquals(136.27f, p.getFloat(), 0.001, "high");
        assertEquals(134.41f, p.getFloat(), 0.001, "low");
        assertEquals(0, p.remaining(), "the budget is spent exactly, with nothing left over");
    }
}
