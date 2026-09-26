package com.ledgercore.evaluation

import spock.lang.Shared
import spock.lang.Specification

class TransactionGeneratorSpec extends Specification {

    @Shared
    List<LabeledTransaction> transactions = new TransactionGenerator(2).generate()

    def "it makes 10,000 transactions with about 5% fraud"() {
        expect:
        transactions.size() == 10_000
        def share = transactions.count { it.fraud() } / transactions.size()
        share >= 0.045 && share <= 0.055
    }

    def "the same seed gives the same transactions"() {
        expect:
        new TransactionGenerator(2).generate() == transactions
    }

    def "a different seed gives different transactions"() {
        expect:
        new TransactionGenerator(1).generate() != transactions
    }

    def "transactions are sorted by time"() {
        expect:
        (1..<transactions.size()).every { !transactions[it].time().isBefore(transactions[it - 1].time()) }
    }

    def "every transaction is a valid transfer"() {
        expect:
        transactions.every { it.amount() > 0 && it.fromAccountId() != it.toAccountId() }
    }

    def "every kind of behaviour appears"() {
        when:
        def kinds = transactions*.kind() as Set

        then:
        kinds == ["regular", "new small", "legit large", "bill split",
                  "takeover burst", "drain", "mule", "low and slow"] as Set
        transactions.findAll { it.fraud() }*.kind() as Set == ["takeover burst", "drain", "mule", "low and slow"] as Set
    }
}
