package com.mrrorigin.workspace;

import java.io.IOException;
import java.io.Writer;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.ObjectMapper;

/**
 * Small, purely technical helper shared by every domain module's {@code *WorkspaceExportService}
 * (#64): bounded keyset-paginated JDBC reads written straight through as NDJSON lines, never
 * buffering a full table into memory. Lives in {@code workspace} because every domain module
 * (`billing`, `revenue`, `attribution`, `reporting`, `notification`, `tracking`) already depends on
 * {@code workspace} as its shared-kernel base per {@code ARCHITECTURE.md}'s module table -- this adds
 * no new dependency edge, only a technical utility with no business logic of its own. Each
 * {@code *WorkspaceExportService} still owns its own table list, column allow-lists, and row mapping;
 * this class only owns the repeated cursor/paging/line-writing mechanics, the same way {@code
 * *WorkspaceDataDeletionService}'s bounded-delete loop is repeated shape without repeated meaning.
 */
public final class WorkspaceExportStreaming {

    private WorkspaceExportStreaming() {}

    /**
     * Streams every row of {@code table} for {@code workspaceId}, ordered and keyset-paginated by a
     * single UUID column ({@code cursorColumn} -- typically the table's {@code id} primary key, or a
     * single-column primary key like {@code project_tracking_retention_settings.project_id}). Each
     * page is fetched, written, and discarded before the next page is fetched, so memory stays
     * bounded to one page of rows regardless of table size. Every emitted line is a JSON object with
     * the mapped columns plus a {@code "table"} discriminator, terminated by {@code \n}.
     */
    public static long streamByColumn(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            Writer out,
            UUID workspaceId,
            String table,
            String columns,
            String cursorColumn,
            int pageSize,
            RowMapper<LinkedHashMap<String, Object>> mapper)
            throws IOException {
        long total = 0;
        UUID cursor = null;
        while (true) {
            List<LinkedHashMap<String, Object>> page = cursor == null
                    ? jdbc.sql("SELECT " + columns + " FROM " + table + " WHERE workspace_id = :w ORDER BY "
                                    + cursorColumn + " LIMIT :size")
                            .param("w", workspaceId)
                            .param("size", pageSize)
                            .query(mapper)
                            .list()
                    : jdbc.sql("SELECT " + columns + " FROM " + table + " WHERE workspace_id = :w AND " + cursorColumn
                                    + " > :cursor ORDER BY " + cursorColumn + " LIMIT :size")
                            .param("w", workspaceId)
                            .param("cursor", cursor)
                            .param("size", pageSize)
                            .query(mapper)
                            .list();
            for (LinkedHashMap<String, Object> row : page) {
                row.put("table", table);
                out.write(objectMapper.writeValueAsString(row));
                out.write('\n');
            }
            total += page.size();
            if (page.size() < pageSize) {
                return total;
            }
            cursor = (UUID) page.get(page.size() - 1).get(cursorColumn);
        }
    }

    /** Reads a Postgres array column ({@code text[]}) as a plain {@code List<String>}, or null. */
    public static List<String> stringArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? null : java.util.Arrays.asList((String[]) array.getArray());
    }

    /**
     * A {@link RowMapper} that reads every column named in the query's own SELECT list (never {@code
     * SELECT *} -- each caller's SQL text is itself the column allow-list) into a {@link
     * LinkedHashMap} keyed by column label, decoding each value by its Postgres wire type rather than
     * leaving JDBC to guess: {@code jsonb}/{@code json} columns are parsed into a nested {@code
     * Map}/{@code List} (so they serialize as nested JSON, not a doubly-escaped string); Postgres
     * array types (wire type names are prefixed with {@code _}, e.g. {@code _text}) become a plain
     * {@code List}; {@code timestamptz} and {@code uuid} are decoded to {@link OffsetDateTime}/{@link
     * UUID} rather than a driver-specific object; everything else falls back to {@link
     * ResultSet#getObject(int)}. One shared mapper covers every owned table across all six domain
     * modules, since which columns are exported is already fully controlled by each service's own
     * explicit SELECT column list, not by this mapper.
     */
    public static RowMapper<LinkedHashMap<String, Object>> genericMapper(ObjectMapper objectMapper) {
        return (rs, rowNum) -> {
            ResultSetMetaData meta = rs.getMetaData();
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String label = meta.getColumnLabel(i);
                String typeName = meta.getColumnTypeName(i);
                Object value;
                if ("jsonb".equals(typeName) || "json".equals(typeName)) {
                    String text = rs.getString(i);
                    value = text == null ? null : objectMapper.readValue(text, Object.class);
                } else if (typeName != null && typeName.startsWith("_")) {
                    Array array = rs.getArray(i);
                    value = array == null ? null : java.util.Arrays.asList((Object[]) array.getArray());
                } else if ("timestamptz".equals(typeName)) {
                    value = rs.getObject(i, OffsetDateTime.class);
                } else if ("uuid".equals(typeName)) {
                    value = rs.getObject(i, UUID.class);
                } else {
                    value = rs.getObject(i);
                }
                row.put(label, value);
            }
            return row;
        };
    }
}
