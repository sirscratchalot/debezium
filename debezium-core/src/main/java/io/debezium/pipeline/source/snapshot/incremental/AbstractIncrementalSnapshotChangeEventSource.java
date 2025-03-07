/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.source.snapshot.incremental;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;

import org.apache.kafka.connect.data.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.annotation.NotThreadSafe;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.DataChangeEventListener;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.ChangeRecordEmitter;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.Partition;
import io.debezium.relational.Column;
import io.debezium.relational.RelationalDatabaseSchema;
import io.debezium.relational.RelationalSnapshotChangeEventSource;
import io.debezium.relational.SnapshotChangeRecordEmitter;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.relational.TableSchema;
import io.debezium.schema.DataCollectionId;
import io.debezium.schema.DatabaseSchema;
import io.debezium.util.Clock;
import io.debezium.util.ColumnUtils;
import io.debezium.util.Strings;
import io.debezium.util.Threads;
import io.debezium.util.Threads.Timer;

/**
 * An incremental snapshot change event source that emits events from a DB log interleaved with snapshot events.
 */
@NotThreadSafe
public abstract class AbstractIncrementalSnapshotChangeEventSource<T extends DataCollectionId> implements IncrementalSnapshotChangeEventSource<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractIncrementalSnapshotChangeEventSource.class);

    private final CommonConnectorConfig connectorConfig;
    private final Clock clock;
    private final RelationalDatabaseSchema databaseSchema;
    private final SnapshotProgressListener progressListener;
    private final DataChangeEventListener dataListener;
    private long totalRowsScanned = 0;

    private Table currentTable;

    protected EventDispatcher<T> dispatcher;
    protected IncrementalSnapshotContext<T> context = null;
    protected JdbcConnection jdbcConnection;
    protected final Map<Struct, Object[]> window = new LinkedHashMap<>();

    public AbstractIncrementalSnapshotChangeEventSource(CommonConnectorConfig config, JdbcConnection jdbcConnection, EventDispatcher<T> dispatcher,
                                                        DatabaseSchema<?> databaseSchema, Clock clock, SnapshotProgressListener progressListener,
                                                        DataChangeEventListener dataChangeEventListener) {
        this.connectorConfig = config;
        this.jdbcConnection = jdbcConnection;
        this.dispatcher = dispatcher;
        this.databaseSchema = (RelationalDatabaseSchema) databaseSchema;
        this.clock = clock;
        this.progressListener = progressListener;
        this.dataListener = dataChangeEventListener;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void closeWindow(Partition partition, String id, OffsetContext offsetContext) throws InterruptedException {
        context = (IncrementalSnapshotContext<T>) offsetContext.getIncrementalSnapshotContext();
        if (!context.closeWindow(id)) {
            return;
        }
        sendWindowEvents(partition, offsetContext);
        readChunk();
    }

    protected String getSignalTableName(String dataCollectionId) {
        return dataCollectionId;
    }

    protected void sendWindowEvents(Partition partition, OffsetContext offsetContext) throws InterruptedException {
        LOGGER.debug("Sending {} events from window buffer", window.size());
        offsetContext.incrementalSnapshotEvents();
        for (Object[] row : window.values()) {
            sendEvent(partition, dispatcher, offsetContext, row);
        }
        offsetContext.postSnapshotCompletion();
        window.clear();
    }

    protected void sendEvent(Partition partition, EventDispatcher<T> dispatcher, OffsetContext offsetContext, Object[] row) throws InterruptedException {
        context.sendEvent(keyFromRow(row));
        offsetContext.event(context.currentDataCollectionId(), clock.currentTimeAsInstant());
        dispatcher.dispatchSnapshotEvent(context.currentDataCollectionId(),
                getChangeRecordEmitter(partition, context.currentDataCollectionId(), offsetContext, row),
                dispatcher.getIncrementalSnapshotChangeEventReceiver(dataListener));
    }

    /**
     * Returns a {@link ChangeRecordEmitter} producing the change records for
     * the given table row.
     */
    protected ChangeRecordEmitter getChangeRecordEmitter(Partition partition, T dataCollectionId,
                                                         OffsetContext offsetContext, Object[] row) {
        return new SnapshotChangeRecordEmitter(partition, offsetContext, row, clock);
    }

    protected void deduplicateWindow(DataCollectionId dataCollectionId, Object key) {
        if (!context.currentDataCollectionId().equals(dataCollectionId)) {
            return;
        }
        if (key instanceof Struct) {
            if (window.remove((Struct) key) != null) {
                LOGGER.info("Removed '{}' from window", key);
            }
        }
    }

    /**
     * Update low watermark for the incremental snapshot chunk
     */
    protected abstract void emitWindowOpen() throws SQLException;

    /**
     * Update high watermark for the incremental snapshot chunk
     */
    protected abstract void emitWindowClose() throws SQLException, InterruptedException;

    protected String buildChunkQuery(Table table) {
        String condition = null;
        // Add condition when this is not the first query
        if (context.isNonInitialChunk()) {
            final StringBuilder sql = new StringBuilder();
            // Window boundaries
            addKeyColumnsToCondition(table, sql, " >= ?");
            sql.append(" AND NOT (");
            addKeyColumnsToCondition(table, sql, " = ?");
            sql.append(")");
            // Table boundaries
            sql.append(" AND ");
            addKeyColumnsToCondition(table, sql, " <= ?");
            condition = sql.toString();
        }
        final String orderBy = table.primaryKeyColumns().stream()
                .map(Column::name)
                .collect(Collectors.joining(", "));
        return jdbcConnection.buildSelectWithRowLimits(table.id(),
                connectorConfig.getIncrementalSnashotChunkSize(),
                "*",
                Optional.ofNullable(condition),
                orderBy);
    }

    protected String buildMaxPrimaryKeyQuery(Table table) {
        final String orderBy = table.primaryKeyColumns().stream()
                .map(Column::name)
                .collect(Collectors.joining(" DESC, ")) + " DESC";
        return jdbcConnection.buildSelectWithRowLimits(table.id(), 1, "*", Optional.empty(), orderBy);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void init(OffsetContext offsetContext) {
        if (offsetContext == null) {
            LOGGER.info("Empty incremental snapshot change event source started, no action needed");
            postIncrementalSnapshotCompleted();
            return;
        }
        context = (IncrementalSnapshotContext<T>) offsetContext.getIncrementalSnapshotContext();
        if (!context.snapshotRunning()) {
            LOGGER.info("No incremental snapshot in progress, no action needed on start");
            postIncrementalSnapshotCompleted();
            return;
        }
        LOGGER.info("Incremental snapshot in progress, need to read new chunk on start");
        try {
            progressListener.snapshotStarted();
            readChunk();
        }
        catch (InterruptedException e) {
            throw new DebeziumException("Reading of an initial chunk after connector restart has been interrupted");
        }
        LOGGER.info("Incremental snapshot in progress, loading of initial chunk completed");
    }

    protected void readChunk() throws InterruptedException {
        if (!context.snapshotRunning()) {
            LOGGER.info("Skipping read chunk because snapshot is not running");
            postIncrementalSnapshotCompleted();
            return;
        }
        try {
            // This commit should be unnecessary and might be removed later
            jdbcConnection.commit();
            preReadChunk(context);
            context.startNewChunk();
            emitWindowOpen();
            while (context.snapshotRunning()) {
                final TableId currentTableId = (TableId) context.currentDataCollectionId();
                currentTable = databaseSchema.tableFor(currentTableId);
                if (currentTable == null) {
                    LOGGER.warn("Schema not found for table '{}', known tables {}", currentTableId, databaseSchema.tableIds());
                    break;
                }
                if (currentTable.primaryKeyColumns().isEmpty()) {
                    LOGGER.warn("Incremental snapshot for table '{}' skipped cause the table has no primary keys", currentTableId);
                    break;
                }
                if (!context.maximumKey().isPresent()) {
                    context.maximumKey(jdbcConnection.queryAndMap(buildMaxPrimaryKeyQuery(currentTable), rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        return keyFromRow(jdbcConnection.rowToArray(currentTable, databaseSchema, rs,
                                ColumnUtils.toArray(rs, currentTable)));
                    }));
                    if (!context.maximumKey().isPresent()) {
                        LOGGER.info(
                                "No maximum key returned by the query, incremental snapshotting of table '{}' finished as it is empty",
                                currentTableId);
                        context.nextDataCollection();
                        continue;
                    }
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("Incremental snapshot for table '{}' will end at position {}", currentTableId,
                                context.maximumKey().orElse(new Object[0]));
                    }
                }
                createDataEventsForTable();
                if (window.isEmpty()) {
                    LOGGER.info("No data returned by the query, incremental snapshotting of table '{}' finished",
                            currentTableId);
                    tableScanCompleted();
                    context.nextDataCollection();
                    if (!context.snapshotRunning()) {
                        progressListener.snapshotCompleted();
                    }
                }
                else {
                    break;
                }
            }
            emitWindowClose();
        }
        catch (SQLException e) {
            throw new DebeziumException(String.format("Database error while executing incremental snapshot for table '%s'", context.currentDataCollectionId()), e);
        }
        finally {
            postReadChunk(context);
            if (!context.snapshotRunning()) {
                postIncrementalSnapshotCompleted();
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void addDataCollectionNamesToSnapshot(List<String> dataCollectionIds, OffsetContext offsetContext) throws InterruptedException {
        context = (IncrementalSnapshotContext<T>) offsetContext.getIncrementalSnapshotContext();
        boolean shouldReadChunk = !context.snapshotRunning();
        final List<T> newDataCollectionIds = context.addDataCollectionNamesToSnapshot(dataCollectionIds);
        if (shouldReadChunk) {
            progressListener.snapshotStarted();
            progressListener.monitoredDataCollectionsDetermined(newDataCollectionIds);
            readChunk();
        }
    }

    protected void addKeyColumnsToCondition(Table table, StringBuilder sql, String predicate) {
        for (Iterator<Column> i = table.primaryKeyColumns().iterator(); i.hasNext();) {
            final Column key = i.next();
            sql.append(key.name()).append(predicate);
            if (i.hasNext()) {
                sql.append(" AND ");
            }
        }
    }

    /**
     * Dispatches the data change events for the records of a single table.
     */
    private void createDataEventsForTable() {
        long exportStart = clock.currentTimeInMillis();
        LOGGER.debug("Exporting data chunk from table '{}' (total {} tables)", currentTable.id(), context.tablesToBeSnapshottedCount());

        final String selectStatement = buildChunkQuery(currentTable);
        LOGGER.debug("\t For table '{}' using select statement: '{}', key: '{}', maximum key: '{}'", currentTable.id(),
                selectStatement, context.chunkEndPosititon(), context.maximumKey().get());

        final TableSchema tableSchema = databaseSchema.schemaFor(currentTable.id());

        try (PreparedStatement statement = readTableChunkStatement(selectStatement);
                ResultSet rs = statement.executeQuery()) {

            final ColumnUtils.ColumnArray columnArray = ColumnUtils.toArray(rs, currentTable);
            long rows = 0;
            Timer logTimer = getTableScanLogTimer();

            Object[] lastRow = null;
            Object[] firstRow = null;
            while (rs.next()) {
                rows++;
                final Object[] row = jdbcConnection.rowToArray(currentTable, databaseSchema, rs, columnArray);
                if (firstRow == null) {
                    firstRow = row;
                }
                final Struct keyStruct = tableSchema.keyFromColumnData(row);
                window.put(keyStruct, row);
                if (logTimer.expired()) {
                    long stop = clock.currentTimeInMillis();
                    LOGGER.debug("\t Exported {} records for table '{}' after {}", rows, currentTable.id(),
                            Strings.duration(stop - exportStart));
                    logTimer = getTableScanLogTimer();
                }
                lastRow = row;
            }
            final Object[] firstKey = keyFromRow(firstRow);
            final Object[] lastKey = keyFromRow(lastRow);
            context.nextChunkPosition(lastKey);
            progressListener.currentChunk(context.currentChunkId(), firstKey, lastKey);
            if (lastRow != null) {
                LOGGER.debug("\t Next window will resume from '{}'", context.chunkEndPosititon());
            }

            LOGGER.debug("\t Finished exporting {} records for window of table table '{}'; total duration '{}'", rows,
                    currentTable.id(), Strings.duration(clock.currentTimeInMillis() - exportStart));
            incrementTableRowsScanned(rows);
        }
        catch (SQLException e) {
            throw new DebeziumException("Snapshotting of table " + currentTable.id() + " failed", e);
        }
    }

    private void incrementTableRowsScanned(long rows) {
        totalRowsScanned += rows;
        progressListener.rowsScanned(currentTable.id(), totalRowsScanned);
    }

    private void tableScanCompleted() {
        progressListener.dataCollectionSnapshotCompleted(currentTable.id(), totalRowsScanned);
        totalRowsScanned = 0;
    }

    protected PreparedStatement readTableChunkStatement(String sql) throws SQLException {
        final PreparedStatement statement = jdbcConnection.readTablePreparedStatement(connectorConfig, sql,
                OptionalLong.empty());
        if (context.isNonInitialChunk()) {
            final Object[] maximumKey = context.maximumKey().get();
            final Object[] chunkEndPosition = context.chunkEndPosititon();
            for (int i = 0; i < chunkEndPosition.length; i++) {
                statement.setObject(i + 1, chunkEndPosition[i]);
                statement.setObject(i + 1 + chunkEndPosition.length, chunkEndPosition[i]);
                statement.setObject(i + 1 + 2 * chunkEndPosition.length, maximumKey[i]);
            }
        }
        return statement;
    }

    private Timer getTableScanLogTimer() {
        return Threads.timer(clock, RelationalSnapshotChangeEventSource.LOG_INTERVAL);
    }

    private Object[] keyFromRow(Object[] row) {
        if (row == null) {
            return null;
        }
        final List<Column> keyColumns = currentTable.primaryKeyColumns();
        final Object[] key = new Object[keyColumns.size()];
        for (int i = 0; i < keyColumns.size(); i++) {
            key[i] = row[keyColumns.get(i).position() - 1];
        }
        return key;
    }

    protected void setContext(IncrementalSnapshotContext<T> context) {
        this.context = context;
    }

    protected void preReadChunk(IncrementalSnapshotContext<T> context) {
        // no-op
    }

    protected void postReadChunk(IncrementalSnapshotContext<T> context) {
        // no-op
    }

    protected void postIncrementalSnapshotCompleted() {
        // no-op
    }
}
