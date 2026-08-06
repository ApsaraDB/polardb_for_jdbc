/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.postgresql;

import io.r2dbc.postgresql.api.PostgresqlConnection;
import io.r2dbc.postgresql.api.PostgresqlResult;
import io.r2dbc.postgresql.util.PostgresqlServerExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Integration tests for PolarDB date type (OID 9002) support.
 * These tests verify that LocalDate can be correctly bound and read from PolarDB date columns
 * when {@code usePolarDbDate} is enabled.
 */
final class PolarDbLocalDateTests {

    @RegisterExtension
    static final PostgresqlServerExtension SERVER = new PostgresqlServerExtension();

    private PostgresqlConnection connection;

    @BeforeEach
    void setUp() {
        PostgresqlConnectionConfiguration configuration = SERVER.configBuilder()
            .usePolarDbDate(true)
            .build();
        connection = new PostgresqlConnectionFactory(configuration).create().block();
    }

    @AfterEach
    void cleanUp() {
        if (connection != null) {
            connection.close().subscribe();
        }
    }

    @Test
    void bindLocalDateToTimestampColumn() {
        SERVER.getJdbcOperations().execute("DROP TABLE IF EXISTS test_polardb_ts");
        SERVER.getJdbcOperations().execute("CREATE TABLE test_polardb_ts (value TIMESTAMP)");

        try {
            LocalDate date = LocalDate.of(2024, 3, 24);

            this.connection.createStatement("INSERT INTO test_polardb_ts (value) VALUES ($1)")
                .bind("$1", date)
                .execute()
                .flatMap(PostgresqlResult::getRowsUpdated)
                .as(StepVerifier::create)
                .expectNext(1L)
                .verifyComplete();

            this.connection.createStatement("SELECT value FROM test_polardb_ts")
                .execute()
                .flatMap(result -> result.map((row, metadata) -> row.get("value", LocalDateTime.class)))
                .as(StepVerifier::create)
                .expectNextMatches(dt -> dt.toLocalDate().equals(date))
                .verifyComplete();
        } finally {
            SERVER.getJdbcOperations().execute("DROP TABLE IF EXISTS test_polardb_ts");
        }
    }

    @Test
    void bindLocalDateToDateColumn() {
        SERVER.getJdbcOperations().execute("DROP TABLE IF EXISTS test_polardb_date");
        SERVER.getJdbcOperations().execute("CREATE TABLE test_polardb_date (value DATE)");

        try {
            LocalDate date = LocalDate.of(2024, 3, 24);

            // With usePolarDbDate=true, LocalDate is encoded with OID 9002 (PolarDB date)
            // which should be accepted by PolarDB date columns
            this.connection.createStatement("INSERT INTO test_polardb_date (value) VALUES ($1)")
                .bind("$1", date)
                .execute()
                .flatMap(PostgresqlResult::getRowsUpdated)
                .as(StepVerifier::create)
                .expectNext(1L)
                .verifyComplete();

            this.connection.createStatement("SELECT value FROM test_polardb_date")
                .execute()
                .flatMap(result -> result.map((row, metadata) -> row.get("value", LocalDate.class)))
                .as(StepVerifier::create)
                .expectNext(date)
                .verifyComplete();
        } finally {
            SERVER.getJdbcOperations().execute("DROP TABLE IF EXISTS test_polardb_date");
        }
    }

    @Test
    void bindNullLocalDateToDateColumn() {
        SERVER.getJdbcOperations().execute("DROP TABLE IF EXISTS test_polardb_null");
        SERVER.getJdbcOperations().execute("CREATE TABLE test_polardb_null (value DATE)");

        try {
            // With usePolarDbDate=true, null LocalDate is encoded with OID 9002
            this.connection.createStatement("INSERT INTO test_polardb_null (value) VALUES ($1)")
                .bindNull("$1", LocalDate.class)
                .execute()
                .flatMap(PostgresqlResult::getRowsUpdated)
                .as(StepVerifier::create)
                .expectNext(1L)
                .verifyComplete();

            this.connection.createStatement("SELECT value FROM test_polardb_null")
                .execute()
                .flatMap(result -> result.map((row, metadata) -> {
                    LocalDate value = row.get("value", LocalDate.class);
                    return value == null ? "NULL" : value.toString();
                }))
                .as(StepVerifier::create)
                .expectNext("NULL")
                .verifyComplete();
        } finally {
            SERVER.getJdbcOperations().execute("DROP TABLE IF EXISTS test_polardb_null");
        }
    }

    @Test
    void bindLocalDateTimeToTimestampColumn() {
        SERVER.getJdbcOperations().execute("DROP TABLE IF EXISTS test_polardb_ldt");
        SERVER.getJdbcOperations().execute("CREATE TABLE test_polardb_ldt (value TIMESTAMP)");

        try {
            LocalDateTime dateTime = LocalDateTime.of(2024, 3, 24, 15, 30, 0);

            this.connection.createStatement("INSERT INTO test_polardb_ldt (value) VALUES ($1)")
                .bind("$1", dateTime)
                .execute()
                .flatMap(PostgresqlResult::getRowsUpdated)
                .as(StepVerifier::create)
                .expectNext(1L)
                .verifyComplete();

            this.connection.createStatement("SELECT value FROM test_polardb_ldt")
                .execute()
                .flatMap(result -> result.map((row, metadata) -> row.get("value", LocalDateTime.class)))
                .as(StepVerifier::create)
                .expectNext(dateTime)
                .verifyComplete();
        } finally {
            SERVER.getJdbcOperations().execute("DROP TABLE IF EXISTS test_polardb_ldt");
        }
    }

    @Test
    void readLocalDateFromDateColumn() {
        SERVER.getJdbcOperations().execute("DROP TABLE IF EXISTS test_polardb_read");
        SERVER.getJdbcOperations().execute("CREATE TABLE test_polardb_read (value DATE)");
        SERVER.getJdbcOperations().execute("INSERT INTO test_polardb_read (value) VALUES ('2024-03-24')");

        try {
            // Read a date value inserted via JDBC and verify it can be decoded
            this.connection.createStatement("SELECT value FROM test_polardb_read")
                .execute()
                .flatMap(result -> result.map((row, metadata) -> row.get("value", LocalDate.class)))
                .as(StepVerifier::create)
                .expectNext(LocalDate.of(2024, 3, 24))
                .verifyComplete();
        } finally {
            SERVER.getJdbcOperations().execute("DROP TABLE IF EXISTS test_polardb_read");
        }
    }
}
