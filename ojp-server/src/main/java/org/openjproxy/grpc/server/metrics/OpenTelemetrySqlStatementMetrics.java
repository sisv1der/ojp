package org.openjproxy.grpc.server.metrics;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenTelemetry-based implementation of {@link SqlStatementMetrics}.
 *
 * <p>Exports the following metrics, all labelled with {@code sql.hash}:</p>
 * <ul>
 *   <li><b>ojp.sql.execution.time</b> – histogram of execution times (ms) per SQL hash</li>
 *   <li><b>ojp.sql.executions.total</b> – total number of executions per SQL hash</li>
 *   <li><b>ojp.sql.slow.executions.total</b> – number of slow executions per SQL hash</li>
 * </ul>
 *
 * <p>All metrics are labelled with {@code sql.hash} so that consumers can group or
 * filter by individual statement identity without embedding raw SQL in metric labels
 * (which would cause unbounded cardinality).</p>
 */
public class OpenTelemetrySqlStatementMetrics implements SqlStatementMetrics {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetrySqlStatementMetrics.class);

    private static final String METER_NAME = "ojp.sql";
    static final AttributeKey<String> SQL_HASH_KEY = AttributeKey.stringKey("sql.hash");

    private final Meter meter;
    private final DoubleHistogram executionTimeHistogram;
    private final LongCounter executionsTotalCounter;
    private final LongCounter slowExecutionsTotalCounter;

    /**
     * Creates an {@link OpenTelemetrySqlStatementMetrics} backed by the given
     * {@link OpenTelemetry} instance.
     *
     * @param openTelemetry the OpenTelemetry instance to use; must not be {@code null}
     */
    public OpenTelemetrySqlStatementMetrics(OpenTelemetry openTelemetry) {
        if (openTelemetry == null) {
            throw new IllegalArgumentException("openTelemetry cannot be null");
        }

        this.meter = openTelemetry.getMeter(METER_NAME);

        this.executionTimeHistogram = meter.histogramBuilder("ojp.sql.execution.time")
                .setDescription("Execution time of SQL statements in milliseconds, keyed by SQL hash")
                .setUnit("ms")
                .build();

        this.executionsTotalCounter = meter.counterBuilder("ojp.sql.executions.total")
                .setDescription("Total number of SQL statement executions, keyed by SQL hash")
                .setUnit("executions")
                .build();

        this.slowExecutionsTotalCounter = meter.counterBuilder("ojp.sql.slow.executions.total")
                .setDescription("Number of SQL statement executions classified as slow, keyed by SQL hash")
                .setUnit("executions")
                .build();

        log.info("OpenTelemetry SQL statement metrics initialized");
    }

    @Override
    public void recordSqlExecution(String sqlHash, long executionTimeMs, boolean isSlow) {
        if (sqlHash == null || sqlHash.isEmpty()) {
            return;
        }
        Attributes attrs = Attributes.of(SQL_HASH_KEY, sqlHash);
        executionTimeHistogram.record(executionTimeMs, attrs);
        executionsTotalCounter.add(1, attrs);
        if (isSlow) {
            slowExecutionsTotalCounter.add(1, attrs);
        }
        log.trace("Recorded SQL execution: hash={}, time={}ms, slow={}", sqlHash, executionTimeMs, isSlow);
    }

    @Override
    public void close() {
        log.info("Closing OpenTelemetry SQL statement metrics");
        // OpenTelemetry meters are managed by the SDK; no explicit cleanup needed.
    }
}
