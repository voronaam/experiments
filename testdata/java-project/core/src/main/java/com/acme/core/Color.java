package com.acme.core;

/** Enum constants with arguments and a constant-specific body, a field, a constructor and a classic switch. */
public enum Color {
    RED("#f00"),
    GREEN("#0f0"),
    BLUE("#00f") {
        @Override
        public boolean warm() {
            return false;
        }
    };

    private final String hex;

    Color(String hex) {
        this.hex = hex;
    }

    public String hex() {
        return hex;
    }

    public boolean warm() {
        switch (this) {
            case RED:
                return true;
            case GREEN:
            case BLUE:
                return false;
            default:
                return hex.isEmpty();
        }
    }
}
