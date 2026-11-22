package com.traderakshak.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Column mapping for the streamed Dhan scrip master.
 *
 * <p>The import used to build a LinkedHashMap per row, so a field was looked up by name against
 * that row's own map. Streaming replaced that with a header index resolved once and
 * {@code at(cols, index, names...)} reading by position - the change that took peak heap from
 * hundreds of megabytes to one chunk. The risk it introduces is different in kind from the one it
 * removed: a mis-resolved index does not fail, it silently writes the wrong column into every one
 * of ~212,000 instrument rows, and the instrument master is what every price lookup resolves
 * symbols through. So the mapping is pinned here.
 */
class DhanScripMasterParsingTest {

    /** Builds the header index exactly as the streaming import does. */
    private static Map<String, Integer> indexOf(String header) {
        Map<String, Integer> index = new LinkedHashMap<>();
        String[] cols = DhanHistoricalBackfillService.splitCsv(header);
        for (int i = 0; i < cols.length; i++) {
            index.put(cols[i].trim().toUpperCase(Locale.ROOT), i);
        }
        return index;
    }

    @Test
    void readsEachFieldFromItsOwnColumn() {
        String header = "SEM_EXM_EXCH_ID,SEM_SEGMENT,SEM_SMST_SECURITY_ID,SEM_TRADING_SYMBOL,"
                      + "SEM_INSTRUMENT_NAME,SM_SYMBOL_NAME,SEM_LOT_UNITS";
        Map<String, Integer> index = indexOf(header);
        String[] row = DhanHistoricalBackfillService.splitCsv("NSE,E,11536,TCS-EQ,EQUITY,TCS,1");

        assertEquals("NSE",    DhanHistoricalBackfillService.at(row, index, "SEM_EXM_EXCH_ID", "EXCH_ID"));
        assertEquals("E",      DhanHistoricalBackfillService.at(row, index, "SEM_SEGMENT", "SEGMENT"));
        assertEquals("11536",  DhanHistoricalBackfillService.at(row, index, "SEM_SMST_SECURITY_ID", "SECURITY_ID"));
        assertEquals("TCS-EQ", DhanHistoricalBackfillService.at(row, index, "SEM_TRADING_SYMBOL", "TRADING_SYMBOL"));
        assertEquals("EQUITY", DhanHistoricalBackfillService.at(row, index, "SEM_INSTRUMENT_NAME", "INSTRUMENT"));
        assertEquals("TCS",    DhanHistoricalBackfillService.at(row, index, "SM_SYMBOL_NAME", "SYMBOL_NAME"));
        assertEquals("1",      DhanHistoricalBackfillService.at(row, index, "SEM_LOT_UNITS", "LOT_SIZE"));
    }

    @Test
    void fallsBackThroughTheAliasListInOrder() {
        // Dhan has shipped both spellings; the import passes several and takes the first present.
        Map<String, Integer> index = indexOf("SECURITY_ID,UNDERLYING_SYMBOL");
        String[] row = DhanHistoricalBackfillService.splitCsv("4567,RELIANCE");

        assertEquals("4567", DhanHistoricalBackfillService.at(
            row, index, "SEM_SMST_SECURITY_ID", "SECURITY_ID", "SMST_SECURITY_ID"),
            "the preferred name is absent, so the second alias must be used");
        assertEquals("RELIANCE", DhanHistoricalBackfillService.at(
            row, index, "UNDERLYING_SYMBOL", "SEM_UNDERLYING_SYMBOL"));
    }

    @Test
    void missingAndBlankColumnsReadAsEmptyNeverAsAnotherColumn() {
        Map<String, Integer> index = indexOf("SECURITY_ID,SEM_EXPIRY_DATE,SEM_OPTION_TYPE");
        String[] row = DhanHistoricalBackfillService.splitCsv("4567,,CE");

        assertEquals("", DhanHistoricalBackfillService.at(row, index, "SEM_EXPIRY_DATE"),
            "an empty cell is empty - it must not fall through to the next alias or column");
        assertEquals("", DhanHistoricalBackfillService.at(row, index, "NO_SUCH_COLUMN"),
            "an unknown column must not resolve to index 0");
        assertEquals("CE", DhanHistoricalBackfillService.at(row, index, "SEM_OPTION_TYPE"));
    }

    @Test
    void shortRowsDoNotReachPastTheirOwnEnd() {
        Map<String, Integer> index = indexOf("A,B,C,D");
        String[] row = DhanHistoricalBackfillService.splitCsv("1,2");      // two cells against a four-column header

        assertEquals("1", DhanHistoricalBackfillService.at(row, index, "A"));
        assertEquals("", DhanHistoricalBackfillService.at(row, index, "D"),
            "a ragged row must read as missing rather than throw - Dhan ships them");
    }

    @Test
    void quotedCellsKeepEmbeddedCommas() {
        Map<String, Integer> index = indexOf("SECURITY_ID,SEM_CUSTOM_SYMBOL,SEM_SEGMENT");
        String[] row = DhanHistoricalBackfillService.splitCsv("99,\"RELIANCE INDUSTRIES, LTD\",E");

        assertEquals("RELIANCE INDUSTRIES, LTD",
            DhanHistoricalBackfillService.at(row, index, "SEM_CUSTOM_SYMBOL", "DISPLAY_NAME"),
            "a comma inside quotes must not split the row and shift every later column");
        assertEquals("E", DhanHistoricalBackfillService.at(row, index, "SEM_SEGMENT"),
            "the column after a quoted comma must still line up");
    }

    @Test
    void headerLookupIsCaseInsensitiveLikeTheImport() {
        Map<String, Integer> index = indexOf("sem_smst_security_id,sem_trading_symbol");
        String[] row = DhanHistoricalBackfillService.splitCsv("777,INFY-EQ");

        assertTrue(index.containsKey("SEM_SMST_SECURITY_ID"),
            "the header is upper-cased once when the index is built");
        assertEquals("777", DhanHistoricalBackfillService.at(row, index, "SEM_SMST_SECURITY_ID"));
    }
}
