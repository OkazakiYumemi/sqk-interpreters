package cn.edu.nju.cs;

final class Value {
    enum Kind {
        INT,
        CHAR,
        BOOLEAN,
        STRING,
        ARRAY,
        CLASS,
        NULL
    }

    private final Kind kind;
    private final Object value;
    private final String typeName; // To keep track of array types like "int[]"
    private final boolean isDecimalLiteral; // true if this INT came from a DECIMAL_LITERAL in char range

    private Value(Kind kind, Object value, String typeName, boolean isDecimalLiteral) {
        this.kind = kind;
        this.value = value;
        this.typeName = typeName;
        this.isDecimalLiteral = isDecimalLiteral;
    }

    static Value ofInt(int value) {
        return new Value(Kind.INT, value, "int", false);
    }

    static Value ofDecimalLiteral(int value) {
        // DECIMAL_LITERAL that is in char range - can be treated as char in assignment/return
        return new Value(Kind.INT, value, "int", true);
    }

    static Value ofChar(int value) {
        return new Value(Kind.CHAR, (int) (byte) value, "char", false);
    }

    static Value ofBoolean(boolean value) {
        return new Value(Kind.BOOLEAN, value, "boolean", false);
    }

    static Value ofString(String value) {
        return new Value(Kind.STRING, value, "string", false);
    }

    static Value ofNull() {
        return new Value(Kind.NULL, null, "null", false);
    }

    /** Create a null value that carries a target type name (e.g. for method return type preservation). */
    static Value ofNullTyped(String typeName) {
        return new Value(Kind.NULL, null, typeName, false);
    }

    static Value ofArray(Value[] elements, String typeName) {
        return new Value(Kind.ARRAY, elements, typeName, false);
    }

    static Value ofClassObj(ObjectInstance obj) {
        return new Value(Kind.CLASS, obj, obj.className, false);
    }

    Kind kind() {
        return kind;
    }

    String getTypeName() {
        return typeName;
    }

    Object getValue() {
        return value;
    }

    boolean isDecimalLiteral() {
        return isDecimalLiteral;
    }

    static String kindName(Kind kind) {
        return switch (kind) {
            case INT -> "int";
            case CHAR -> "char";
            case BOOLEAN -> "boolean";
            case STRING -> "string";
            case ARRAY -> "array";
            case CLASS -> "class";
            case NULL -> "null";
        };
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
        if (kind == Kind.NULL) return "null";
        if (kind == Kind.ARRAY) return arrayToString((Value[]) value);
        if (kind == Kind.CLASS) {
            ObjectInstance obj = (ObjectInstance) value;
            return obj == null ? "null" : obj.className;
        }
        return switch (kind) {
            case INT -> Integer.toString((Integer) value);
            case CHAR -> Character.toString((char) (((Integer) value) & 0xFF));
            case BOOLEAN -> Boolean.toString((Boolean) value);
            case STRING -> (String) value;
            default -> "";
        };
    }

    String displayString() {
        if (kind == Kind.NULL) return "null";
        if (kind == Kind.ARRAY) return arrayToString((Value[]) value);
        if (kind == Kind.CLASS) {
            ObjectInstance obj = (ObjectInstance) value;
            return obj == null ? "null" : obj.className;
        }
        return switch (kind) {
            case INT -> Integer.toString((Integer) value);
            case CHAR -> Character.toString((char) (((Integer) value) & 0xFF));
            case BOOLEAN -> Boolean.toString((Boolean) value);
            case STRING -> (String) value;
            default -> "";
        };
    }

    private String arrayToString(Value[] arr) {
        if (arr == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < arr.length; i++) {
            sb.append(arr[i] == null ? "null" : arr[i].displayString());
            if (i < arr.length - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }

    /** Returns the ObjectInstance for CLASS values, or null. */
    ObjectInstance asClassObj() {
        if (kind == Kind.NULL) return null;
        if (kind != Kind.CLASS) {
            throw new EvalException("Class object value required.");
        }
        return (ObjectInstance) value;
    }

    boolean isClassType() {
        return kind == Kind.CLASS || kind == Kind.NULL;
    }

    static Value rewrapArrayType(Value arrayVal, String typeName) {
        if (arrayVal.kind != Kind.ARRAY) return arrayVal;
        Value[] elements = (Value[]) arrayVal.getValue();
        Value[] newElements = new Value[elements.length];
        String innerTypeName = typeName.endsWith("[]") ? typeName.substring(0, typeName.length() - 2) : typeName;
        for (int i = 0; i < elements.length; i++) {
            Value el = elements[i];
            if (el != null && el.kind == Kind.ARRAY) {
                newElements[i] = rewrapArrayType(el, innerTypeName);
            } else {
                newElements[i] = el;
            }
        }
        return Value.ofArray(newElements, typeName);
    }
}
