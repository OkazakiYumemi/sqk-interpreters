package cn.edu.nju.cs;

final class Value {
    enum Kind {
        INT,
        CHAR,
        BOOLEAN,
        STRING
    }

    private final Kind kind;
    private final Object value;

    private Value(Kind kind, Object value) {
        this.kind = kind;
        this.value = value;
    }

    static Value ofInt(int value) {
        return new Value(Kind.INT, value);
    }

    static Value ofChar(int value) {
        return new Value(Kind.CHAR, (int) (byte) value);
    }

    static Value ofBoolean(boolean value) {
        return new Value(Kind.BOOLEAN, value);
    }

    static Value ofString(String value) {
        return new Value(Kind.STRING, value);
    }

    Kind kind() {
        return kind;
    }

    boolean isIntegral() {
        return kind == Kind.INT || kind == Kind.CHAR;
    }

    int asIntegral() {
        if (!isIntegral()) {
            throw new EvalException("Integral value required.");
        }
        return (Integer) value;
    }

    boolean asBoolean() {
        if (kind != Kind.BOOLEAN) {
            throw new EvalException("Boolean value required.");
        }
        return (Boolean) value;
    }

    String asString() {
        if (kind != Kind.STRING) {
            throw new EvalException("String value required.");
        }
        return (String) value;
    }

    String toConcatString() {
        return switch (kind) {
            case INT -> Integer.toString((Integer) value);
            case CHAR -> Character.toString((char) (((Integer) value) & 0xFF));
            case BOOLEAN -> Boolean.toString((Boolean) value);
            case STRING -> (String) value;
        };
    }

    String displayString() {
        return switch (kind) {
            case INT -> Integer.toString((Integer) value);
            case CHAR -> Character.toString((char) (((Integer) value) & 0xFF));
            case BOOLEAN -> Boolean.toString((Boolean) value);
            case STRING -> (String) value;
        };
    }
}
