package com.ledgercore

import com.ledgercore.config.Database
import com.zaxxer.hikari.HikariDataSource
import groovy.sql.Sql
import spock.lang.Shared
import spock.lang.Specification

import java.sql.SQLException

class SchemaSpec extends Specification {

    @Shared
    HikariDataSource dataSource

    @Shared
    Sql sql

    def setupSpec() {
        dataSource = Database.connect(TestEnv.appConfig())
        sql = new Sql(dataSource)
    }

    def cleanupSpec() {
        dataSource.close()
    }

    def "migration creates one funding account for each supported currency"() {
        when:
        def rows = sql.rows("select currency, balance from accounts where type = 'SYSTEM' order by currency")

        then:
        rows*.currency == ["EUR", "GBP", "USD"]
        // money only leaves a funding account, so its balance is never positive
        rows*.balance.every { it <= 0 }
    }

    def "database rejects a second funding account for the same currency"() {
        when:
        sql.execute("insert into accounts (type, owner_name, currency) values ('SYSTEM', 'funding', 'EUR')")

        then:
        def e = thrown(SQLException)
        e.message.contains("accounts_one_system_account_per_currency")
    }

    def "database rejects a negative balance on a customer account"() {
        given:
        def id = insertCustomerAccount("EUR")

        when:
        sql.executeUpdate("update accounts set balance = -1 where id = ?", [id])

        then:
        def e = thrown(SQLException)
        e.message.contains("accounts_balance_not_negative")
    }

    def "database allows a negative balance on a system account"() {
        given:
        def connection = dataSource.connection
        connection.autoCommit = false
        def tx = new Sql(connection)

        when:
        def updated = tx.executeUpdate("update accounts set balance = -100 where type = 'SYSTEM' and currency = 'EUR'")

        then:
        updated == 1

        cleanup:
        connection.rollback()
        connection.close()
    }

    def "database rejects the account currency #currency"() {
        when:
        insertCustomerAccount(currency)

        then:
        thrown(SQLException)

        where:
        currency << ["eur", "EU", "EURO", "E1R"]
    }

    def "database rejects a transfer from an account to itself"() {
        given:
        def id = insertCustomerAccount("EUR")

        when:
        insertTransfer(id, id, 100, "EUR")

        then:
        def e = thrown(SQLException)
        e.message.contains("transfers_different_accounts")
    }

    def "database rejects a transfer with amount #amount"() {
        given:
        def from = insertCustomerAccount("EUR")
        def to = insertCustomerAccount("EUR")

        when:
        insertTransfer(from, to, amount, "EUR")

        then:
        def e = thrown(SQLException)
        e.message.contains("transfers_amount_positive")

        where:
        amount << [0, -100]
    }

    def "database rejects a transfer whose currency does not match the accounts"() {
        given:
        def from = insertCustomerAccount("EUR")
        def to = insertCustomerAccount("EUR")

        when:
        insertTransfer(from, to, 100, "GBP")

        then:
        def e = thrown(SQLException)
        e.message.contains("transfers_from_account_fk")
    }

    def "database rejects a ledger entry whose currency does not match its account"() {
        given:
        def from = insertCustomerAccount("EUR")
        def to = insertCustomerAccount("EUR")
        def transferId = insertTransfer(from, to, 100, "EUR")

        when:
        sql.execute("insert into ledger_entries (transfer_id, account_id, amount, currency) values (?, ?, 100, 'GBP')",
                [transferId, to])

        then:
        def e = thrown(SQLException)
        e.message.contains("ledger_entries_account_fk")
    }

    def "database rejects a ledger entry with zero amount"() {
        given:
        def from = insertCustomerAccount("EUR")
        def to = insertCustomerAccount("EUR")
        def transferId = insertTransfer(from, to, 100, "EUR")

        when:
        sql.execute("insert into ledger_entries (transfer_id, account_id, amount, currency) values (?, ?, 0, 'EUR')",
                [transferId, to])

        then:
        def e = thrown(SQLException)
        e.message.contains("ledger_entries_amount_not_zero")
    }

    def "database accepts the transfer status #status"() {
        given:
        def from = insertCustomerAccount("EUR")
        def to = insertCustomerAccount("EUR")

        when:
        sql.execute("""insert into transfers (from_account_id, to_account_id, amount, currency, status)
                values (?, ?, 100, 'EUR', ?)""", [from, to, status])

        then:
        noExceptionThrown()

        where:
        status << ["COMPLETED", "PENDING_REVIEW", "DECLINED"]
    }

    def "database rejects an unknown transfer status"() {
        given:
        def from = insertCustomerAccount("EUR")
        def to = insertCustomerAccount("EUR")

        when:
        sql.execute("""insert into transfers (from_account_id, to_account_id, amount, currency, status)
                values (?, ?, 100, 'EUR', 'APPROVED')""", [from, to])

        then:
        def e = thrown(SQLException)
        e.message.contains("transfers_status_known")
    }

    def "database rejects a second row with the same idempotency key"() {
        given:
        sql.execute("insert into idempotency_keys (key, request_hash, status, response_body) values ('dup', 'h', 201, '{}')")

        when:
        sql.execute("insert into idempotency_keys (key, request_hash, status, response_body) values ('dup', 'h', 201, '{}')")

        then:
        def e = thrown(SQLException)
        e.message.contains("idempotency_keys_pkey")
    }

    private long insertCustomerAccount(String currency) {
        def row = sql.firstRow("insert into accounts (type, owner_name, currency) values ('CUSTOMER', 'test', ?) returning id",
                [currency])
        row.id as long
    }

    private long insertTransfer(long from, long to, long amount, String currency) {
        def row = sql.firstRow("""insert into transfers (from_account_id, to_account_id, amount, currency, status)
                values (?, ?, ?, ?, 'COMPLETED') returning id""", [from, to, amount, currency])
        row.id as long
    }
}
