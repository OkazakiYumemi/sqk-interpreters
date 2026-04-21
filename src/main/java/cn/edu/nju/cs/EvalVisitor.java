package cn.edu.nju.cs;

public class EvalVisitor extends MiniJavaParserBaseVisitor<Value> {
    @Override
    public Value visitCompilationUnit(MiniJavaParser.CompilationUnitContext ctx) {
        return visit(ctx.expression());
    }

    @Override
    public Value visitPrimary(MiniJavaParser.PrimaryContext ctx) {
        if (ctx.literal() != null) {
            return visit(ctx.literal());
        }
        return visit(ctx.expression());
    }

    @Override
    public Value visitLiteral(MiniJavaParser.LiteralContext ctx) {
        if (ctx.DECIMAL_LITERAL() != null) {
            String text = ctx.DECIMAL_LITERAL().getText().replace("_", "");
            try {
                return Value.ofInt(Integer.parseInt(text));
            } catch (NumberFormatException ex) {
                throw new EvalException("Invalid integer literal: " + text, ex);
            }
        }

        if (ctx.BOOL_LITERAL() != null) {
            return Value.ofBoolean(Boolean.parseBoolean(ctx.BOOL_LITERAL().getText()));
        }

        if (ctx.STRING_LITERAL() != null) {
            String raw = ctx.STRING_LITERAL().getText();
            return Value.ofString(raw.substring(1, raw.length() - 1));
        }

        if (ctx.CHAR_LITERAL() != null) {
            String raw = ctx.CHAR_LITERAL().getText();
            String body = raw.substring(1, raw.length() - 1);
            if (body.length() != 1) {
                throw new EvalException("Only simple char literals are supported.");
            }
            return Value.ofChar(body.charAt(0));
        }

        throw new EvalException("Unknown literal node.");
    }

    @Override
    public Value visitExpression(MiniJavaParser.ExpressionContext ctx) {
        if (ctx.primary() != null) {
            return visit(ctx.primary());
        }

        if (ctx.postfix != null) {
            throw new EvalException("Postfix ++/-- is invalid in this lab.");
        }

        if (ctx.prefix != null) {
            Value operand = visit(ctx.expression(0));
            return evalPrefix(ctx.prefix.getType(), operand);
        }

        if (ctx.primitiveType() != null) {
            Value operand = visit(ctx.expression(0));
            return evalCast(ctx.primitiveType().getText(), operand);
        }

        if (ctx.bop != null) {
            int op = ctx.bop.getType();

            if (op == MiniJavaParser.QUESTION) {
                return evalTernary(ctx);
            }

            if (op == MiniJavaParser.AND) {
                return evalAnd(ctx);
            }

            if (op == MiniJavaParser.OR) {
                return evalOr(ctx);
            }

            Value left = visit(ctx.expression(0));
            Value right = visit(ctx.expression(1));
            return evalBinary(op, left, right);
        }

        throw new EvalException("Unsupported expression form.");
    }

    private Value evalPrefix(int op, Value operand) {
        return switch (op) {
            case MiniJavaParser.ADD -> Value.ofInt(requireIntegral(operand));
            case MiniJavaParser.SUB -> Value.ofInt(-requireIntegral(operand));
            case MiniJavaParser.BANG -> Value.ofBoolean(!requireBoolean(operand));
            case MiniJavaParser.TILDE -> Value.ofInt(~requireIntegral(operand));
            case MiniJavaParser.INC, MiniJavaParser.DEC -> throw new EvalException("Prefix ++/-- is invalid in this lab.");
            default -> throw new EvalException("Unsupported prefix operator.");
        };
    }

    private Value evalCast(String typeName, Value operand) {
        if ("int".equals(typeName)) {
            return Value.ofInt(requireIntegral(operand));
        }

        if ("char".equals(typeName)) {
            return Value.ofChar(requireIntegral(operand));
        }

        throw new EvalException("Only int/char casts are supported.");
    }

    private Value evalTernary(MiniJavaParser.ExpressionContext ctx) {
        boolean cond = requireBoolean(visit(ctx.expression(0)));
        return cond ? visit(ctx.expression(1)) : visit(ctx.expression(2));
    }

    private Value evalAnd(MiniJavaParser.ExpressionContext ctx) {
        boolean left = requireBoolean(visit(ctx.expression(0)));
        if (!left) {
            return Value.ofBoolean(false);
        }
        boolean right = requireBoolean(visit(ctx.expression(1)));
        return Value.ofBoolean(right);
    }

    private Value evalOr(MiniJavaParser.ExpressionContext ctx) {
        boolean left = requireBoolean(visit(ctx.expression(0)));
        if (left) {
            return Value.ofBoolean(true);
        }
        boolean right = requireBoolean(visit(ctx.expression(1)));
        return Value.ofBoolean(right);
    }

    private Value evalBinary(int op, Value left, Value right) {
        return switch (op) {
            case MiniJavaParser.MUL -> Value.ofInt(requireIntegral(left) * requireIntegral(right));
            case MiniJavaParser.DIV -> evalDiv(left, right);
            case MiniJavaParser.MOD -> evalMod(left, right);
            case MiniJavaParser.ADD -> evalAdd(left, right);
            case MiniJavaParser.SUB -> evalSub(left, right);
            case MiniJavaParser.LSHIFT -> Value.ofInt(requireIntegral(left) << requireIntegral(right));
            case MiniJavaParser.RSHIFT -> Value.ofInt(requireIntegral(left) >> requireIntegral(right));
            case MiniJavaParser.URSHIFT -> Value.ofInt(requireIntegral(left) >>> requireIntegral(right));
            case MiniJavaParser.LT -> Value.ofBoolean(requireIntegral(left) < requireIntegral(right));
            case MiniJavaParser.GT -> Value.ofBoolean(requireIntegral(left) > requireIntegral(right));
            case MiniJavaParser.LE -> Value.ofBoolean(requireIntegral(left) <= requireIntegral(right));
            case MiniJavaParser.GE -> Value.ofBoolean(requireIntegral(left) >= requireIntegral(right));
            case MiniJavaParser.EQUAL -> evalEquality(left, right, true);
            case MiniJavaParser.NOTEQUAL -> evalEquality(left, right, false);
            case MiniJavaParser.BITAND -> Value.ofInt(requireIntegral(left) & requireIntegral(right));
            case MiniJavaParser.CARET -> Value.ofInt(requireIntegral(left) ^ requireIntegral(right));
            case MiniJavaParser.BITOR -> Value.ofInt(requireIntegral(left) | requireIntegral(right));
            case MiniJavaParser.ASSIGN,
                MiniJavaParser.ADD_ASSIGN,
                MiniJavaParser.SUB_ASSIGN,
                MiniJavaParser.MUL_ASSIGN,
                MiniJavaParser.DIV_ASSIGN,
                MiniJavaParser.AND_ASSIGN,
                MiniJavaParser.OR_ASSIGN,
                MiniJavaParser.XOR_ASSIGN,
                MiniJavaParser.RSHIFT_ASSIGN,
                MiniJavaParser.URSHIFT_ASSIGN,
                MiniJavaParser.LSHIFT_ASSIGN,
                MiniJavaParser.MOD_ASSIGN -> throw new EvalException("Assignment operators are unsupported in this lab.");
            default -> throw new EvalException("Unsupported operator.");
        };
    }

    private Value evalDiv(Value left, Value right) {
        int r = requireIntegral(right);
        if (r == 0) {
            throw new EvalException("Division by zero.");
        }
        return Value.ofInt(requireIntegral(left) / r);
    }

    private Value evalMod(Value left, Value right) {
        int r = requireIntegral(right);
        if (r == 0) {
            throw new EvalException("Modulo by zero.");
        }
        return Value.ofInt(requireIntegral(left) % r);
    }

    private Value evalAdd(Value left, Value right) {
        if (left.kind() == Value.Kind.STRING || right.kind() == Value.Kind.STRING) {
            return Value.ofString(left.toConcatString() + right.toConcatString());
        }
        return Value.ofInt(requireIntegral(left) + requireIntegral(right));
    }

    private Value evalSub(Value left, Value right) {
        if (left.kind() == Value.Kind.STRING || right.kind() == Value.Kind.STRING) {
            throw new EvalException("Subtraction does not support strings.");
        }
        return Value.ofInt(requireIntegral(left) - requireIntegral(right));
    }

    private Value evalEquality(Value left, Value right, boolean isEqual) {
        boolean result;

        if (left.isIntegral() && right.isIntegral()) {
            result = left.asIntegral() == right.asIntegral();
        } else if (left.kind() == Value.Kind.BOOLEAN && right.kind() == Value.Kind.BOOLEAN) {
            result = left.asBoolean() == right.asBoolean();
        } else if (left.kind() == Value.Kind.STRING && right.kind() == Value.Kind.STRING) {
            result = left.asString().equals(right.asString());
        } else {
            throw new EvalException("Invalid operand types for equality.");
        }

        return Value.ofBoolean(isEqual ? result : !result);
    }

    private int requireIntegral(Value value) {
        return value.asIntegral();
    }

    private boolean requireBoolean(Value value) {
        return value.asBoolean();
    }
}
