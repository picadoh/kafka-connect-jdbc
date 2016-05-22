/**
 * Copyright 2015 Datamountaineer.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package com.datamountaineer.streamreactor.connect.jdbc.sink.writer;

import com.datamountaineer.streamreactor.connect.jdbc.sink.DatabaseChangesExecutor;
import com.datamountaineer.streamreactor.connect.jdbc.sink.DatabaseMetadata;
import com.datamountaineer.streamreactor.connect.jdbc.sink.DatabaseMetadataProvider;
import com.datamountaineer.streamreactor.connect.jdbc.sink.DbWriter;
import com.datamountaineer.streamreactor.connect.jdbc.sink.HikariHelper;
import com.datamountaineer.streamreactor.connect.jdbc.sink.common.ParameterValidator;
import com.datamountaineer.streamreactor.connect.jdbc.sink.config.FieldsMappings;
import com.datamountaineer.streamreactor.connect.jdbc.sink.config.JdbcSinkSettings;
import com.datamountaineer.streamreactor.connect.jdbc.sink.writer.dialect.DbDialect;
import com.google.common.collect.Iterators;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.*;
import java.util.Date;
import java.util.Set;
import java.util.List;
import java.util.Collection;
import java.util.HashSet;



/**
 * Responsible for taking a sequence of SinkRecord and writing them to the database
 */
public final class JdbcDbWriter implements DbWriter {
  private static final Logger logger = LoggerFactory.getLogger(JdbcDbWriter.class);

  private final PreparedStatementBuilder statementBuilder;
  private final ErrorHandlingPolicy errorHandlingPolicy;
  private final DatabaseChangesExecutor databaseChangesExecutor;
  private int retries;
  private Date lastError;
  private String lastErrorMessage;
  private final int maxRetries;

  //provides connection pooling
  private final HikariDataSource dataSource;

  /**
   * @param hikariDataSource        - The database connection pooling
   * @param statementBuilder        - Returns a sequence of PreparedStatement to process
   * @param errorHandlingPolicy     - An instance of the error handling approach
   * @param databaseChangesExecutor - Contains the database metadata (tables and their columns)
   * @param retries                 - Number of attempts to run when a SQLException occurs
   */
  public JdbcDbWriter(final HikariDataSource hikariDataSource,
                      final PreparedStatementBuilder statementBuilder,
                      final ErrorHandlingPolicy errorHandlingPolicy,
                      final DatabaseChangesExecutor databaseChangesExecutor,
                      final int retries) {
    ParameterValidator.notNull(hikariDataSource, "hikariDataSource");
    ParameterValidator.notNull(statementBuilder, "statementBuilder");
    ParameterValidator.notNull(databaseChangesExecutor, "databaseChangesExecutor");


    this.dataSource = hikariDataSource;
    this.statementBuilder = statementBuilder;
    this.errorHandlingPolicy = errorHandlingPolicy;
    this.databaseChangesExecutor = databaseChangesExecutor;
    this.retries = retries;
    this.maxRetries = retries;
  }

  /**
   * Writes the given records to the database
   *
   * @param records - The sequence of records to insert
   */
  @Override
  public void write(final Collection<SinkRecord> records) {
    if (records.isEmpty()) {
      logger.warn("Received empty sequence of SinkRecord");
    } else {

      Connection connection = null;
      Collection<PreparedStatement> statements = null;
      try {
        connection = dataSource.getConnection();
        final PreparedStatementContext statementContext = statementBuilder.build(records, connection);
        statements = statementContext.getPreparedStatements();
        if (!statements.isEmpty()) {

          //begin transaction
          connection.setAutoCommit(false);
          //handle possible database changes (new tables, new columns)
          databaseChangesExecutor.handleChanges(statementContext.getTablesToColumnsMap());

          for (final PreparedStatement statement : statements) {
            if (statementBuilder.isBatching()) {
              statement.executeBatch();
            } else {
              statement.execute();
            }
          }
          //commit the transaction
          connection.commit();

          if (maxRetries != retries) {
            retries = maxRetries;
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS'Z'");
            logger.info(String.format("Recovered from error % at %s", formatter.format(lastError), lastErrorMessage));
          }
        }
      } catch (SQLException sqlException) {
        final SinkRecord firstRecord = Iterators.getNext(records.iterator(), null);
        assert firstRecord != null;
        logger.error(String.format("Following error has occurred inserting data starting at topic:%s offset:%d partition:%d",
                firstRecord.topic(),
                firstRecord.kafkaOffset(),
                firstRecord.kafkaPartition()));
        logger.error(sqlException.getMessage());

        if (connection != null) {
          //rollback the transaction
          try {
            connection.rollback();
          } catch (Throwable t) {
            logger.error(t.getMessage());
          }
        }

        retries--;
        lastError = new Date();
        lastErrorMessage = sqlException.getMessage();
        errorHandlingPolicy.handle(records, sqlException, retries);
      } finally {
        if (statements != null) {
          for (final PreparedStatement statement : statements) {
            try {
              statement.close();
            } catch (Throwable t) {
              logger.error(t.getMessage());
            }
          }
        }

        if (connection != null) {
          try {
            connection.close();
          } catch (Throwable t) {
            logger.error(t.getMessage());
          }
        }
      }
    }
  }

  @Override
  public void close() {
    dataSource.close();
  }

  /**
   * Creates an instance of JdbcDbWriter from the Jdbc sink settings.
   *
   * @param settings - Holds the sink settings
   * @return Returns a new instsance of JdbcDbWriter
   */
  public static JdbcDbWriter from(final JdbcSinkSettings settings,
                                  final DatabaseMetadataProvider databaseMetadataProvider) {

    final HikariDataSource connectionPool = HikariHelper.from(settings.getConnection(),
            settings.getUser(),
            settings.getPassword());

    final DatabaseMetadata databaseMetadata = databaseMetadataProvider.get(connectionPool);

    final PreparedStatementBuilder statementBuilder = PreparedStatementBuilderHelper.from(settings, databaseMetadata);
    logger.info(String.format("Created PreparedStatementBuilder as %s", statementBuilder.getClass().getCanonicalName()));
    final ErrorHandlingPolicy errorHandlingPolicy = ErrorHandlingPolicyHelper.from(settings.getErrorPolicy());
    logger.info(String.format("Created the error policy handler as %s", errorHandlingPolicy.getClass().getCanonicalName()));

    final List<FieldsMappings> mappingsList = settings.getMappings();
    final Set<String> tablesAllowingAutoCreate = new HashSet<>();
    final Set<String> tablesAllowingSchemaEvolution = new HashSet<>();
    for (FieldsMappings fm : mappingsList) {
      if (fm.autoCreateTable()) {
        tablesAllowingAutoCreate.add(fm.getTableName());
      }
      if (fm.evolveTableSchema()) {
        tablesAllowingSchemaEvolution.add(fm.getTableName());
      }
    }

    final DbDialect dbDialect = DbDialect.fromConnectionString(settings.getConnection());

    final DatabaseChangesExecutor databaseChangesExecutor = new DatabaseChangesExecutor(
            connectionPool,
            tablesAllowingAutoCreate,
            tablesAllowingSchemaEvolution,
            databaseMetadata,
            dbDialect,
            settings.getRetries());

    return new JdbcDbWriter(connectionPool,
            statementBuilder,
            errorHandlingPolicy,
            databaseChangesExecutor,
            settings.getRetries());
  }

  /**
   * Get the prepared statement builder
   *
   * @return a PreparedStatementBuilder
   */
  public PreparedStatementBuilder getStatementBuilder() {
    return statementBuilder;
  }

  /**
   * Get the Error Handling Policy for the task
   *
   * @return A ErrorHandlingPolicy
   */
  public ErrorHandlingPolicy getErrorHandlingPolicy() {
    return errorHandlingPolicy;
  }
}