/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.sqlserver;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.sqlserver.SqlServerConnectorConfig.SnapshotMode;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.StreamingChangeEventSource;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.schema.SchemaChangeEvent.SchemaChangeEventType;
import io.debezium.util.Clock;
import io.debezium.util.ElapsedTimeStrategy;
import io.debezium.util.Metronome;

/**
 * <p>A {@link StreamingChangeEventSource} based on SQL Server change data capture functionality.
 * A main loop polls database DDL change and change data tables and turns them into change events.</p>
 *
 * <p>The connector uses CDC functionality of SQL Server that is implemented as as a process that monitors
 * source table and write changes from the table into the change table.</p>
 *
 * <p>The main loop keeps a pointer to the LSN of changes that were already processed. It queries all change
 * tables and get result set of changes. It always finds the smallest LSN across all tables and the change
 * is converted into the event message and sent downstream. The process repeats until all result sets are
 * empty. The LSN is marked and the procedure repeats.</p>
 *
 * <p>The schema changes detection follows the procedure recommended by SQL Server CDC documentation.
 * The database operator should create one more capture process (and table) when a table schema is updated.
 * The code detects presence of two change tables for a single source table. It decides which table is the new one
 * depending on LSNs stored in them. The loop streams changes from the older table till there are events in new
 * table with the LSN larger than in the old one. Then the change table is switched and streaming is executed
 * from the new one.</p>
 *
 * @author Jiri Pechanec
 */
public class SqlServerStreamingChangeEventSource implements StreamingChangeEventSource<SqlServerPartition, SqlServerOffsetContext> {

    private static final Pattern MISSING_CDC_FUNCTION_CHANGES_ERROR = Pattern.compile("Invalid object name 'cdc.fn_cdc_get_all_changes_(.*)'\\.");

    private static final Logger LOGGER = LoggerFactory.getLogger(SqlServerStreamingChangeEventSource.class);

    private static final Duration DEFAULT_INTERVAL_BETWEEN_COMMITS = Duration.ofMinutes(1);
    private static final int INTERVAL_BETWEEN_COMMITS_BASED_ON_POLL_FACTOR = 3;

    /**
     * Connection used for reading CDC tables.
     */
    private final SqlServerConnection dataConnection;

    /**
     * A separate connection for retrieving timestamps; without it, adaptive
     * buffering will not work.
     *
     * @link https://docs.microsoft.com/en-us/sql/connect/jdbc/using-adaptive-buffering?view=sql-server-2017#guidelines-for-using-adaptive-buffering
     */
    private final SqlServerConnection metadataConnection;

    private final EventDispatcher<TableId> dispatcher;
    private final ErrorHandler errorHandler;
    private final Clock clock;
    private final SqlServerDatabaseSchema schema;
    private final Duration pollInterval;
    private final SqlServerConnectorConfig connectorConfig;

    private final ElapsedTimeStrategy pauseBetweenCommits;

    public SqlServerStreamingChangeEventSource(SqlServerConnectorConfig connectorConfig, SqlServerConnection dataConnection,
                                               SqlServerConnection metadataConnection, EventDispatcher<TableId> dispatcher, ErrorHandler errorHandler, Clock clock,
                                               SqlServerDatabaseSchema schema) {
        this.connectorConfig = connectorConfig;
        this.dataConnection = dataConnection;
        this.metadataConnection = metadataConnection;
        this.dispatcher = dispatcher;
        this.errorHandler = errorHandler;
        this.clock = clock;
        this.schema = schema;
        this.pollInterval = connectorConfig.getPollInterval();
        final Duration intervalBetweenCommitsBasedOnPoll = this.pollInterval.multipliedBy(INTERVAL_BETWEEN_COMMITS_BASED_ON_POLL_FACTOR);
        this.pauseBetweenCommits = ElapsedTimeStrategy.constant(clock,
                DEFAULT_INTERVAL_BETWEEN_COMMITS.compareTo(intervalBetweenCommitsBasedOnPoll) > 0
                        ? DEFAULT_INTERVAL_BETWEEN_COMMITS.toMillis()
                        : intervalBetweenCommitsBasedOnPoll.toMillis());
        this.pauseBetweenCommits.hasElapsed();
    }

    @Override
    public void execute(ChangeEventSourceContext context, SqlServerPartition partition, SqlServerOffsetContext offsetContext) throws InterruptedException {
        if (connectorConfig.getSnapshotMode().equals(SnapshotMode.INITIAL_ONLY)) {
            LOGGER.info("Streaming is not enabled in current configuration");
            return;
        }

        final Metronome metronome = Metronome.sleeper(pollInterval, clock);
        final Queue<SqlServerChangeTable> schemaChangeCheckpoints = new PriorityQueue<>((x, y) -> x.getStopLsn().compareTo(y.getStopLsn()));
        try {
            final AtomicReference<SqlServerChangeTable[]> tablesSlot = new AtomicReference<>(getCdcTablesToQuery(partition, offsetContext));

            final TxLogPosition lastProcessedPositionOnStart = offsetContext.getChangePosition();
            final long lastProcessedEventSerialNoOnStart = offsetContext.getEventSerialNo();
            LOGGER.info("Last position recorded in offsets is {}[{}]", lastProcessedPositionOnStart, lastProcessedEventSerialNoOnStart);
            final AtomicBoolean changesStoppedBeingMonotonic = new AtomicBoolean(false);
            final int maxTransactionsPerIteration = connectorConfig.getMaxTransactionsPerIteration();

            TxLogPosition lastProcessedPosition = lastProcessedPositionOnStart;

            // LSN should be increased for the first run only immediately after snapshot completion
            // otherwise we might skip an incomplete transaction after restart
            boolean shouldIncreaseFromLsn = offsetContext.isSnapshotCompleted();
            while (context.isRunning()) {
                commitTransaction();
                final Lsn toLsn = getToLsn(dataConnection, lastProcessedPosition, maxTransactionsPerIteration);

                // Shouldn't happen if the agent is running, but it is better to guard against such situation
                if (!toLsn.isAvailable()) {
                    LOGGER.warn("No maximum LSN recorded in the database; please ensure that the SQL Server Agent is running");
                    metronome.pause();
                    continue;
                }
                // There is no change in the database
                if (toLsn.compareTo(lastProcessedPosition.getCommitLsn()) <= 0 && shouldIncreaseFromLsn) {
                    LOGGER.debug("No change in the database");
                    metronome.pause();
                    continue;
                }

                // Reading interval is inclusive so we need to move LSN forward but not for first
                // run as TX might not be streamed completely
                final Lsn fromLsn = lastProcessedPosition.getCommitLsn().isAvailable() && shouldIncreaseFromLsn
                        ? dataConnection.incrementLsn(lastProcessedPosition.getCommitLsn())
                        : lastProcessedPosition.getCommitLsn();
                shouldIncreaseFromLsn = true;

                while (!schemaChangeCheckpoints.isEmpty()) {
                    migrateTable(partition, schemaChangeCheckpoints, offsetContext);
                }
                if (!dataConnection.listOfNewChangeTables(fromLsn, toLsn).isEmpty()) {
                    final SqlServerChangeTable[] tables = getCdcTablesToQuery(partition, offsetContext);
                    tablesSlot.set(tables);
                    for (SqlServerChangeTable table : tables) {
                        if (table.getStartLsn().isBetween(fromLsn, toLsn)) {
                            LOGGER.info("Schema will be changed for {}", table);
                            schemaChangeCheckpoints.add(table);
                        }
                    }
                }
                try {
                    dataConnection.getChangesForTables(tablesSlot.get(), fromLsn, toLsn, resultSets -> {

                        long eventSerialNoInInitialTx = 1;
                        final int tableCount = resultSets.length;
                        final SqlServerChangeTablePointer[] changeTables = new SqlServerChangeTablePointer[tableCount];
                        final SqlServerChangeTable[] tables = tablesSlot.get();

                        for (int i = 0; i < tableCount; i++) {
                            changeTables[i] = new SqlServerChangeTablePointer(tables[i], resultSets[i],
                                    connectorConfig.getSourceTimestampMode());
                            changeTables[i].next();
                        }

                        for (;;) {
                            SqlServerChangeTablePointer tableWithSmallestLsn = null;
                            for (SqlServerChangeTablePointer changeTable : changeTables) {
                                if (changeTable.isCompleted()) {
                                    continue;
                                }
                                if (tableWithSmallestLsn == null || changeTable.compareTo(tableWithSmallestLsn) < 0) {
                                    tableWithSmallestLsn = changeTable;
                                }
                            }
                            if (tableWithSmallestLsn == null) {
                                // No more LSNs available
                                break;
                            }

                            if (!(tableWithSmallestLsn.getChangePosition().isAvailable() && tableWithSmallestLsn.getChangePosition().getInTxLsn().isAvailable())) {
                                LOGGER.error("Skipping change {} as its LSN is NULL which is not expected", tableWithSmallestLsn);
                                tableWithSmallestLsn.next();
                                continue;
                            }

                            if (tableWithSmallestLsn.isNewTransaction() && changesStoppedBeingMonotonic.get()) {
                                LOGGER.info("Resetting changesStoppedBeingMonotonic as transaction changes");
                                changesStoppedBeingMonotonic.set(false);
                            }

                            // After restart for changes that are not monotonic to avoid data loss
                            if (tableWithSmallestLsn.isCurrentPositionSmallerThanPreviousPosition()) {
                                LOGGER.info("Disabling skipping changes due to not monotonic order of changes");
                                changesStoppedBeingMonotonic.set(true);
                            }

                            // After restart for changes that were executed before the last committed offset
                            if (!changesStoppedBeingMonotonic.get() &&
                                    tableWithSmallestLsn.getChangePosition().compareTo(lastProcessedPositionOnStart) < 0) {
                                LOGGER.info("Skipping change {} as its position is smaller than the last recorded position {}", tableWithSmallestLsn,
                                        lastProcessedPositionOnStart);
                                tableWithSmallestLsn.next();
                                continue;
                            }
                            // After restart for change that was the last committed and operations in it before the last committed offset
                            if (!changesStoppedBeingMonotonic.get() && tableWithSmallestLsn.getChangePosition().compareTo(lastProcessedPositionOnStart) == 0
                                    && eventSerialNoInInitialTx <= lastProcessedEventSerialNoOnStart) {
                                LOGGER.info("Skipping change {} as its order in the transaction {} is smaller than or equal to the last recorded operation {}[{}]",
                                        tableWithSmallestLsn, eventSerialNoInInitialTx, lastProcessedPositionOnStart, lastProcessedEventSerialNoOnStart);
                                eventSerialNoInInitialTx++;
                                tableWithSmallestLsn.next();
                                continue;
                            }
                            if (tableWithSmallestLsn.getChangeTable().getStopLsn().isAvailable() &&
                                    tableWithSmallestLsn.getChangeTable().getStopLsn().compareTo(tableWithSmallestLsn.getChangePosition().getCommitLsn()) <= 0) {
                                LOGGER.debug("Skipping table change {} as its stop LSN is smaller than the last recorded LSN {}", tableWithSmallestLsn,
                                        tableWithSmallestLsn.getChangePosition());
                                tableWithSmallestLsn.next();
                                continue;
                            }
                            LOGGER.trace("Processing change {}", tableWithSmallestLsn);
                            LOGGER.trace("Schema change checkpoints {}", schemaChangeCheckpoints);
                            if (!schemaChangeCheckpoints.isEmpty()) {
                                if (tableWithSmallestLsn.getChangePosition().getCommitLsn().compareTo(schemaChangeCheckpoints.peek().getStartLsn()) >= 0) {
                                    migrateTable(partition, schemaChangeCheckpoints, offsetContext);
                                }
                            }
                            final TableId tableId = tableWithSmallestLsn.getChangeTable().getSourceTableId();
                            final int operation = tableWithSmallestLsn.getOperation();
                            final Object[] data = tableWithSmallestLsn.getData();

                            // UPDATE consists of two consecutive events, first event contains
                            // the row before it was updated and the second the row after
                            // it was updated
                            int eventCount = 1;
                            if (operation == SqlServerChangeRecordEmitter.OP_UPDATE_BEFORE) {
                                if (!tableWithSmallestLsn.next() || tableWithSmallestLsn.getOperation() != SqlServerChangeRecordEmitter.OP_UPDATE_AFTER) {
                                    throw new IllegalStateException("The update before event at " + tableWithSmallestLsn.getChangePosition() + " for table " + tableId
                                            + " was not followed by after event.\n Please report this as a bug together with a events around given LSN.");
                                }
                                eventCount = 2;
                            }
                            final Object[] dataNext = (operation == SqlServerChangeRecordEmitter.OP_UPDATE_BEFORE) ? tableWithSmallestLsn.getData() : null;

                            offsetContext.setChangePosition(tableWithSmallestLsn.getChangePosition(), eventCount);
                            offsetContext.event(
                                    tableWithSmallestLsn.getChangeTable().getSourceTableId(),
                                    connectorConfig.getSourceTimestampMode().getTimestamp(
                                            metadataConnection, clock, tableWithSmallestLsn.getResultSet()));

                            dispatcher
                                    .dispatchDataChangeEvent(
                                            tableId,
                                            new SqlServerChangeRecordEmitter(
                                                    partition,
                                                    offsetContext,
                                                    operation,
                                                    data,
                                                    dataNext,
                                                    clock));
                            tableWithSmallestLsn.next();
                        }
                    });
                    lastProcessedPosition = TxLogPosition.valueOf(toLsn);
                    // Terminate the transaction otherwise CDC could not be disabled for tables
                    dataConnection.rollback();
                }
                catch (SQLException e) {
                    tablesSlot.set(processErrorFromChangeTableQuery(e, tablesSlot.get()));
                }
            }
        }
        catch (Exception e) {
            errorHandler.setProducerThrowable(e);
        }
    }

    private void commitTransaction() throws SQLException {
        // When reading from read-only Always On replica the default and only transaction isolation
        // is snapshot. This means that CDC metadata are not visible for long-running transactions.
        // It is thus necessary to restart the transaction before every read.
        // For R/W database it is important to execute regular commits to maintain the size of TempDB
        if (connectorConfig.isReadOnlyDatabaseConnection() || pauseBetweenCommits.hasElapsed()) {
            dataConnection.commit();
        }
    }

    private void migrateTable(SqlServerPartition partition, final Queue<SqlServerChangeTable> schemaChangeCheckpoints, SqlServerOffsetContext offsetContext)
            throws InterruptedException, SQLException {
        final SqlServerChangeTable newTable = schemaChangeCheckpoints.poll();
        LOGGER.info("Migrating schema to {}", newTable);
        Table tableSchema = metadataConnection.getTableSchemaFromTable(newTable);
        dispatcher.dispatchSchemaChangeEvent(newTable.getSourceTableId(),
                new SqlServerSchemaChangeEventEmitter(partition, offsetContext, newTable, tableSchema,
                        SchemaChangeEventType.ALTER));
        newTable.setSourceTable(tableSchema);
    }

    private SqlServerChangeTable[] processErrorFromChangeTableQuery(SQLException exception, SqlServerChangeTable[] currentChangeTables) throws Exception {
        final Matcher m = MISSING_CDC_FUNCTION_CHANGES_ERROR.matcher(exception.getMessage());
        if (m.matches()) {
            final String captureName = m.group(1);
            LOGGER.info("Table is no longer captured with capture instance {}", captureName);
            return Arrays.asList(currentChangeTables).stream()
                    .filter(x -> !x.getCaptureInstance().equals(captureName))
                    .collect(Collectors.toList()).toArray(new SqlServerChangeTable[0]);
        }
        throw exception;
    }

    private SqlServerChangeTable[] getCdcTablesToQuery(SqlServerPartition partition, SqlServerOffsetContext offsetContext) throws SQLException, InterruptedException {
        final Set<SqlServerChangeTable> cdcEnabledTables = dataConnection.listOfChangeTables();
        if (cdcEnabledTables.isEmpty()) {
            LOGGER.warn("No table has enabled CDC or security constraints prevents getting the list of change tables");
        }

        final Map<TableId, List<SqlServerChangeTable>> includeListCdcEnabledTables = cdcEnabledTables.stream()
                .filter(changeTable -> {
                    if (connectorConfig.getTableFilters().dataCollectionFilter().isIncluded(changeTable.getSourceTableId())) {
                        return true;
                    }
                    else {
                        LOGGER.info("CDC is enabled for table {} but the table is not whitelisted by connector", changeTable);
                        return false;
                    }
                })
                .collect(Collectors.groupingBy(x -> x.getSourceTableId()));

        if (includeListCdcEnabledTables.isEmpty()) {
            LOGGER.warn(
                    "No whitelisted table has enabled CDC, whitelisted table list does not contain any table with CDC enabled or no table match the white/blacklist filter(s)");
        }

        final List<SqlServerChangeTable> tables = new ArrayList<>();
        for (List<SqlServerChangeTable> captures : includeListCdcEnabledTables.values()) {
            SqlServerChangeTable currentTable = captures.get(0);
            if (captures.size() > 1) {
                SqlServerChangeTable futureTable;
                if (captures.get(0).getStartLsn().compareTo(captures.get(1).getStartLsn()) < 0) {
                    futureTable = captures.get(1);
                }
                else {
                    currentTable = captures.get(1);
                    futureTable = captures.get(0);
                }
                currentTable.setStopLsn(futureTable.getStartLsn());
                futureTable.setSourceTable(dataConnection.getTableSchemaFromTable(futureTable));
                tables.add(futureTable);
                LOGGER.info("Multiple capture instances present for the same table: {} and {}", currentTable, futureTable);
            }
            if (schema.tableFor(currentTable.getSourceTableId()) == null) {
                LOGGER.info("Table {} is new to be monitored by capture instance {}", currentTable.getSourceTableId(), currentTable.getCaptureInstance());
                // We need to read the source table schema - nullability information cannot be obtained from change table
                // There might be no start LSN in the new change table at this time so current timestamp is used
                offsetContext.event(
                        currentTable.getSourceTableId(),
                        Instant.now());
                dispatcher.dispatchSchemaChangeEvent(
                        currentTable.getSourceTableId(),
                        new SqlServerSchemaChangeEventEmitter(
                                partition,
                                offsetContext,
                                currentTable,
                                dataConnection.getTableSchemaFromTable(currentTable),
                                SchemaChangeEventType.CREATE));
            }

            // If a column was renamed, then the old capture instance had been dropped and a new one
            // created. In consequence, a table with out-dated schema might be assigned here.
            // A proper value will be set when migration happens.
            currentTable.setSourceTable(schema.tableFor(currentTable.getSourceTableId()));
            tables.add(currentTable);
        }

        return tables.toArray(new SqlServerChangeTable[tables.size()]);
    }

    /**
     * @return the log sequence number up until which the connector should query changes from the database.
     */
    private Lsn getToLsn(SqlServerConnection connection, TxLogPosition lastProcessedPosition,
                         int maxTransactionsPerIteration)
            throws SQLException {

        if (maxTransactionsPerIteration == 0) {
            return connection.getMaxTransactionLsn();
        }

        final Lsn fromLsn = lastProcessedPosition.getCommitLsn();

        if (!fromLsn.isAvailable()) {
            return connection.getNthTransactionLsnFromBeginning(maxTransactionsPerIteration);
        }

        return connection.getNthTransactionLsnFromLast(fromLsn, maxTransactionsPerIteration);
    }
}
