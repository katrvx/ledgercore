package com.ledgercore.evaluation

import spock.lang.Specification

class ConfusionSpec extends Specification {

    def "counts every combination of label and flag"() {
        given:
        def confusion = new Confusion()

        when:
        8.times { confusion.add(true, true) }
        2.times { confusion.add(true, false) }
        4.times { confusion.add(false, true) }
        86.times { confusion.add(false, false) }

        then:
        confusion.truePositives() == 8
        confusion.falseNegatives() == 2
        confusion.falsePositives() == 4
        confusion.trueNegatives() == 86

        and:
        confusion.recall() == 0.8d
        confusion.precision() == 8d / 12d
        confusion.falsePositiveRate() == 4d / 90d
    }

    def "empty denominators give 0 instead of dividing by zero"() {
        expect:
        new Confusion().recall() == 0d
        new Confusion().precision() == 0d
        new Confusion().falsePositiveRate() == 0d
    }
}
