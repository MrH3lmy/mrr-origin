package com.mrrorigin.reporting;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Minimal RFC 4180 writer (UTF-8, CRLF line endings, quote-on-demand, {@code ""} escaped quotes) for
 * #26's CSV exports. Writes one row at a time directly to the supplied {@link Writer} -- never builds
 * a full CSV string in memory -- so a caller streaming rows from the database can flush as it goes.
 */
final class CsvWriter {
    private CsvWriter() {}

    static void writeRow(Writer out, List<String> fields) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.write(",");
            }
            out.write(escape(fields.get(i)));
        }
        out.write("\r\n");
    }

    private static String escape(String field) {
        if (field == null || field.isEmpty()) {
            return "";
        }
        boolean needsQuoting =
                field.indexOf(',') >= 0 || field.indexOf('"') >= 0 || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return field;
        }
        return "\"" + field.replace("\"", "\"\"") + "\"";
    }
}
