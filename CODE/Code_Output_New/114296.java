package com.ava.dex.dexapp.dataquality.profiling.strategy;

/*
 * Story ID: 114296
 * Title: Implement Databricks-Specific SQL Dialect & Data Profiling Strategy
 * Language: Java — the story explicitly asks for a Databricks dialect strategy in a Java package and references an existing Java class (DialectFactory.java).
 *
 * What this file does:
 * Provides a Databricks-specific SQL dialect strategy for data profiling queries, including row counts,
 * null counts, approximate distinct counts, min/max values, string length statistics, regex pattern
 * profiling, and TABLESAMPLE-based sampling support. The file also includes assumption-based support
 * types needed to keep the deliverable as a single source file.
 *
 * Assumptions:
 * - ASSUMPTION: supporting interfaces/enums/models referenced by the story were not provided in repo content,
 *   so compatible single-file versions are included below.
 * - ASSUMPTION: persistence into dq_column_profiling and dq_table_profiling is handled by the existing profiling
 *   pipeline; this strategy generates the Databricks-compatible SQL consumed by that pipeline.
 * - ASSUMPTION: regex pattern profiling is implemented as grouped counts for caller-supplied regex expressions.
 */
class DatabricksDialectStrategy implements DatabricksDialectStrategy.DialectStrategy {

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.DATABRICKS;
    }

    @Override
    public String buildTableRowCountQuery(final QualifiedTable table, final SamplingConfig samplingConfig) {
        return "SELECT COUNT(*) AS total_rows FROM " + tableReference(table, samplingConfig);
    }

    @Override
    public String buildNullCountQuery(final QualifiedTable table,
                                      final ColumnProfileRequest column,
                                      final SamplingConfig samplingConfig) {
        final String columnRef = quoteIdentifier(column.columnName());
        return "SELECT COUNT(*) AS null_count FROM " + tableReference(table, samplingConfig)
                + " WHERE " + columnRef + " IS NULL";
    }

    @Override
    public String buildDistinctCountQuery(final QualifiedTable table,
                                          final ColumnProfileRequest column,
                                          final SamplingConfig samplingConfig) {
        final String columnRef = quoteIdentifier(column.columnName());
        return "SELECT APPROX_COUNT_DISTINCT(" + columnRef + ") AS distinct_count FROM "
                + tableReference(table, samplingConfig);
    }

    @Override
    public String buildMinMaxQuery(final QualifiedTable table,
                                   final ColumnProfileRequest column,
                                   final SamplingConfig samplingConfig) {
        final String columnRef = quoteIdentifier(column.columnName());
        return "SELECT MIN(" + columnRef + ") AS min_value, MAX(" + columnRef + ") AS max_value FROM "
                + tableReference(table, samplingConfig);
    }

    @Override
    public String buildStringLengthStatsQuery(final QualifiedTable table,
                                              final ColumnProfileRequest column,
                                              final SamplingConfig samplingConfig) {
        final String columnRef = quoteIdentifier(column.columnName());
        return "SELECT "
                + "MIN(LENGTH(" + columnRef + ")) AS min_length, "
                + "MAX(LENGTH(" + columnRef + ")) AS max_length, "
                + "AVG(LENGTH(" + columnRef + ")) AS avg_length "
                + "FROM " + tableReference(table, samplingConfig)
                + " WHERE " + columnRef + " IS NOT NULL";
    }

    @Override
    public String buildRegexPatternQuery(final QualifiedTable table,
                                         final ColumnProfileRequest column,
                                         final RegexPatternProfile patternProfile,
                                         final SamplingConfig samplingConfig) {
        final String columnRef = quoteIdentifier(column.columnName());
        final String safeAlias = quoteIdentifier(patternProfile.alias());
        final String escapedRegex = escapeSqlLiteral(patternProfile.regex());
        return "SELECT '" + escapeSqlLiteral(patternProfile.alias()) + "' AS pattern_name, COUNT(*) AS pattern_count "
                + "FROM " + tableReference(table, samplingConfig)
                + " WHERE " + columnRef + " RLIKE '" + escapedRegex + "'"
                + " GROUP BY " + safeAlias.replace("`", "")
                + "";
    }

    @Override
    public String buildColumnProfileQuery(final QualifiedTable table,
                                          final ColumnProfileRequest column,
                                          final SamplingConfig samplingConfig) {
        final String columnRef = quoteIdentifier(column.columnName());
        final StringBuilder query = new StringBuilder();
        query.append("SELECT ")
                .append("COUNT(*) AS total_rows, ")
                .append("SUM(CASE WHEN ").append(columnRef).append(" IS NULL THEN 1 ELSE 0 END) AS null_count, ")
                .append("APPROX_COUNT_DISTINCT(").append(columnRef).append(") AS distinct_count, ")
                .append("MIN(").append(columnRef).append(") AS min_value, ")
                .append("MAX(").append(columnRef).append(") AS max_value");

        if (column.isStringLike()) {
            query.append(", MIN(LENGTH(").append(columnRef).append(")) AS min_length")
                    .append(", MAX(LENGTH(").append(columnRef).append(")) AS max_length")
                    .append(", AVG(LENGTH(").append(columnRef).append(")) AS avg_length");
        }

        query.append(" FROM ").append(tableReference(table, samplingConfig));
        return query.toString();
    }

    @Override
    public String buildTableProfileInsertSql(final TableProfilingResult result) {
        return "INSERT INTO dq_table_profiling (datasource_name, catalog_name, schema_name, table_name, total_rows, sample_percent) VALUES ("
                + stringLiteral(result.datasourceName()) + ", "
                + stringLiteral(result.table().catalog()) + ", "
                + stringLiteral(result.table().schema()) + ", "
                + stringLiteral(result.table().table()) + ", "
                + result.totalRows() + ", "
                + nullableDecimal(result.samplePercent())
                + ")";
    }

    @Override
    public String buildColumnProfileInsertSql(final ColumnProfilingResult result) {
        return "INSERT INTO dq_column_profiling (datasource_name, catalog_name, schema_name, table_name, column_name, total_rows, null_count, distinct_count, min_value, max_value, min_length, max_length, avg_length) VALUES ("
                + stringLiteral(result.datasourceName()) + ", "
                + stringLiteral(result.table().catalog()) + ", "
                + stringLiteral(result.table().schema()) + ", "
                + stringLiteral(result.table().table()) + ", "
                + stringLiteral(result.columnName()) + ", "
                + result.totalRows() + ", "
                + result.nullCount() + ", "
                + result.distinctCount() + ", "
                + nullableString(result.minValue()) + ", "
                + nullableString(result.maxValue()) + ", "
                + nullableLong(result.minLength()) + ", "
                + nullableLong(result.maxLength()) + ", "
                + nullableDecimal(result.avgLength())
                + ")";
    }

    private String tableReference(final QualifiedTable table, final SamplingConfig samplingConfig) {
        final String baseTable = qualifyTable(table);
        if (samplingConfig == null || !samplingConfig.enabled()) {
            return baseTable;
        }
        return baseTable + " TABLESAMPLE (" + samplingConfig.percent() + " PERCENT)";
    }

    private String qualifyTable(final QualifiedTable table) {
        final StringBuilder value = new StringBuilder();
        if (hasText(table.catalog())) {
            value.append(quoteIdentifier(table.catalog())).append('.');
        }
        if (hasText(table.schema())) {
            value.append(quoteIdentifier(table.schema())).append('.');
        }
        value.append(quoteIdentifier(table.table()));
        return value.toString();
    }

    private String quoteIdentifier(final String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private String stringLiteral(final String value) {
        return "'" + escapeSqlLiteral(value) + "'";
    }

    private String nullableString(final String value) {
        return value == null ? "NULL" : stringLiteral(value);
    }

    private String nullableLong(final Long value) {
        return value == null ? "NULL" : Long.toString(value);
    }

    private String nullableDecimal(final Double value) {
        return value == null ? "NULL" : Double.toString(value);
    }

    private String escapeSqlLiteral(final String value) {
        return value.replace("'", "''");
    }

    private boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }

    // ASSUMPTION: not found in repo content
    interface DialectStrategy {
        DatabaseType getDatabaseType();

        String buildTableRowCountQuery(QualifiedTable table, SamplingConfig samplingConfig);

        String buildNullCountQuery(QualifiedTable table, ColumnProfileRequest column, SamplingConfig samplingConfig);

        String buildDistinctCountQuery(QualifiedTable table, ColumnProfileRequest column, SamplingConfig samplingConfig);

        String buildMinMaxQuery(QualifiedTable table, ColumnProfileRequest column, SamplingConfig samplingConfig);

        String buildStringLengthStatsQuery(QualifiedTable table, ColumnProfileRequest column, SamplingConfig samplingConfig);

        String buildRegexPatternQuery(QualifiedTable table,
                                      ColumnProfileRequest column,
                                      RegexPatternProfile patternProfile,
                                      SamplingConfig samplingConfig);

        String buildColumnProfileQuery(QualifiedTable table, ColumnProfileRequest column, SamplingConfig samplingConfig);

        String buildTableProfileInsertSql(TableProfilingResult result);

        String buildColumnProfileInsertSql(ColumnProfilingResult result);
    }

    // ASSUMPTION: not found in repo content
    enum DatabaseType {
        DATABRICKS
    }

    // ASSUMPTION: not found in repo content
    record QualifiedTable(String catalog, String schema, String table) {
    }

    // ASSUMPTION: not found in repo content
    record SamplingConfig(boolean enabled, double percent) {
    }

    // ASSUMPTION: not found in repo content
    record ColumnProfileRequest(String columnName, ColumnDataType dataType) {
        boolean isStringLike() {
            return dataType == ColumnDataType.STRING;
        }
    }

    // ASSUMPTION: not found in repo content
    enum ColumnDataType {
        STRING,
        NUMBER,
        DATE,
        TIMESTAMP,
        BOOLEAN,
        OTHER
    }

    // ASSUMPTION: not found in repo content
    record RegexPatternProfile(String alias, String regex) {
    }

    // ASSUMPTION: not found in repo content
    record TableProfilingResult(String datasourceName,
                                QualifiedTable table,
                                long totalRows,
                                Double samplePercent) {
    }

    // ASSUMPTION: not found in repo content
    record ColumnProfilingResult(String datasourceName,
                                 QualifiedTable table,
                                 String columnName,
                                 long totalRows,
                                 long nullCount,
                                 long distinctCount,
                                 String minValue,
                                 String maxValue,
                                 Long minLength,
                                 Long maxLength,
                                 Double avgLength) {
    }

    // ASSUMPTION: not found in repo content
    static final class DialectFactory {
        private DialectFactory() {
        }

        static DialectStrategy forDatabaseType(final DatabaseType databaseType) {
            if (databaseType == DatabaseType.DATABRICKS) {
                return new DatabricksDialectStrategy();
            }
            throw new IllegalArgumentException("Unsupported database type: " + databaseType);
        }
    }
}
