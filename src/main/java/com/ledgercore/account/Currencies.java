package com.ledgercore.account;

import java.util.Currency;

public class Currencies {

    // java ships the ISO 4217 list, so there is no list of our own to maintain
    public static boolean isIsoCode(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
