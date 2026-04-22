package cn.edu.nju.cs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EvalVisitor extends MiniJavaParserBaseVisitor<Value> {
    private static final class BreakSignal extends RuntimeException {
    }

    private static final class ContinueSignal extends RuntimeException {
    }

    private static final class VariableBinding {
        private final Value.Kind declaredType;
        private Value value;

        private VariableBinding(Value.Kind declaredType, Value value) {
            this.declaredType = declaredType;
            this.value = value;
        }
    }

    private static final class ScopeFrame {
        private final int depth;
        private final Map<String, VariableBinding> variables = new HashMap<>();

        private ScopeFrame(int depth) {
            this.depth = depth;
        }
    }

    private final Deque<ScopeFrame> scopeStack = new ArrayDeque<>();
    private int loopDepth = 0;

    @Override
    public Value visitCompilationUnit(MiniJavaParser.CompilationUnitContext ctx) {
        visit(ctx.block());
        return null;
    }

    @Override
    public Value visitBlock(MiniJavaParser.BlockContext ctx) {
        enterScope();
        try {
            for (MiniJavaParser.BlockStatementContext blockStatementContext : ctx.blockStatement()) {
                visit(blockStatementContext);
            }
            exitScope();
            return null;
        } catch (RuntimeException ex) {
            handleScopeExitOnException(ex);
            throw ex;
        }
    }

    @Override
    public Value visitBlockStatement(MiniJavaParser.BlockStatementContext ctx) {
        if (ctx.localVariableDeclaration() != null) {
            visit(ctx.localVariableDeclaration());
            return null;
        }
        return visit(ctx.statement());
    }

    @Override
    public Value visitLocalVariableDeclaration(MiniJavaParser.LocalVariableDeclarationContext ctx) {
        Value.Kind declaredType = parsePrimitiveType(ctx.primitiveType().getText());
        String identifier = ctx.identifier().getText();
        ScopeFrame scope = currentScope();

        if (scope.variables.containsKey(identifier)) {
            throw new EvalException("Variable already declared in current scope: " + identifier);
        }

        Value value;
        if (ctx.expression() == null) {
            value = defaultValueForType(declaredType);
        } else {
            Value rhs = visit(ctx.expression());
            value = applySimpleAssignment(declaredType, rhs);
        }

        scope.variables.put(identifier, new VariableBinding(declaredType, value));
        return null;
    }

    @Override
    public Value visitStatement(MiniJavaParser.StatementContext ctx) {
        if (ctx.block() != null) {
            return visit(ctx.block());
        }

        if (ctx.IF() != null) {
            boolean condition = requireBoolean(visit(ctx.parExpression().expression()));
            if (condition) {
                return visit(ctx.statement(0));
            }

            if (ctx.ELSE() != null) {
                return visit(ctx.statement(1));
            }

            return null;
        }

        if (ctx.WHILE() != null) {
            return evalWhileStatement(ctx);
        }

        if (ctx.FOR() != null) {
            return evalForStatement(ctx);
        }

        if (ctx.BREAK() != null) {
            ensureInsideLoop("break");
            throw new BreakSignal();
        }

        if (ctx.CONTINUE() != null) {
            ensureInsideLoop("continue");
            throw new ContinueSignal();
        }

        if (ctx.expression() != null) {
            visit(ctx.expression());
            return null;
        }

        return null;
    }

    private Value evalWhileStatement(MiniJavaParser.StatementContext ctx) {
        loopDepth++;
        try {
            while (requireBoolean(visit(ctx.parExpression().expression()))) {
                try {
                    visit(ctx.statement(0));
                } catch (ContinueSignal signal) {
                    continue;
                } catch (BreakSignal signal) {
                    break;
                }
            }
            return null;
        } finally {
            loopDepth--;
        }
    }

    private Value evalForStatement(MiniJavaParser.StatementContext ctx) {
        MiniJavaParser.ForControlContext control = ctx.forControl();
        // boolean hasImplicitScope = hasForImplicitScope(control);
        // if (hasImplicitScope) {
        //     enterScope();
        // }
        enterScope();
        loopDepth++;
        try {
            executeForInit(control);
            while (evaluateForCondition(control)) {
                try {
                    visit(ctx.statement(0));
                } catch (ContinueSignal signal) {
                    executeForUpdate(control);
                    continue;
                } catch (BreakSignal signal) {
                    break;
                }
                executeForUpdate(control);
            }
            // if (hasImplicitScope) {
            //     exitScope();
            // }
            exitScope();
            return null;
        } catch (RuntimeException ex) {
            // if (hasImplicitScope) {
            //     handleScopeExitOnException(ex);
            // }
            handleScopeExitOnException(ex);
            throw ex;
        } finally {
            loopDepth--;
        }
    }

    // private boolean hasForImplicitScope(MiniJavaParser.ForControlContext control) {
    //     return control.forInit() != null && control.forInit().localVariableDeclaration() != null;
    // }

    private void ensureInsideLoop(String keyword) {
        if (loopDepth <= 0) {
            throw new EvalException(keyword + " can only be used inside loops.");
        }
    }

    private void executeForInit(MiniJavaParser.ForControlContext control) {
        if (control.forInit() != null) {
            visit(control.forInit());
        }
    }

    private boolean evaluateForCondition(MiniJavaParser.ForControlContext control) {
        if (control.expression() == null) {
            return true;
        }
        return requireBoolean(visit(control.expression()));
    }

    private void executeForUpdate(MiniJavaParser.ForControlContext control) {
        if (control.expressionList() != null) {
            visit(control.expressionList());
        }
    }

    @Override
    public Value visitExpressionList(MiniJavaParser.ExpressionListContext ctx) {
        Value result = null;
        for (MiniJavaParser.ExpressionContext expressionContext : ctx.expression()) {
            result = visit(expressionContext);
        }
        return result;
    }

    @Override
    public Value visitPrimary(MiniJavaParser.PrimaryContext ctx) {
        if (ctx.literal() != null) {
            return visit(ctx.literal());
        }

        if (ctx.identifier() != null) {
            return resolveVariable(ctx.identifier().getText()).value;
        }

        return visit(ctx.expression());
    }

    @Override
    public Value visitLiteral(MiniJavaParser.LiteralContext ctx) {
        if (ctx.DECIMAL_LITERAL() != null) {
            return Value.ofInt(parseDecimalLiteral(ctx.DECIMAL_LITERAL().getText()));
        }

        if (ctx.BOOL_LITERAL() != null) {
            return Value.ofBoolean(Boolean.parseBoolean(ctx.BOOL_LITERAL().getText()));
        }
        
        if (ctx.CHAR_LITERAL() != null) {
            String raw = ctx.CHAR_LITERAL().getText();
            String content = raw.substring(1, raw.length() - 1);
            String decoded = unescape(content);
            if (decoded.length() != 1) {
                throw new EvalException("Invalid char literal.");
            }
            return Value.ofChar(decoded.charAt(0));
        }

        if (ctx.STRING_LITERAL() != null) {
            String raw = ctx.STRING_LITERAL().getText();
            String content = raw.substring(1, raw.length() - 1);
            return Value.ofString(unescape(content));
        }

        // if (ctx.NULL_LITERAL() != null) {
        //     throw new EvalException("Null literal is unsupported.");
        // }

        throw new EvalException("Unknown literal node.");
    }

    @Override
    public Value visitExpression(MiniJavaParser.ExpressionContext ctx) {
        if (ctx.primary() != null) {
            return visit(ctx.primary());
        }

        if (ctx.postfix != null) {
            return evalIncDec(ctx.expression(0), ctx.postfix.getType() == MiniJavaParser.INC, true);
        }

        if (ctx.prefix != null) {
            int op = ctx.prefix.getType();
            if (op == MiniJavaParser.INC || op == MiniJavaParser.DEC) {
                return evalIncDec(ctx.expression(0), op == MiniJavaParser.INC, false);
            }
            Value operand = visit(ctx.expression(0));
            return evalPrefix(op, operand);
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

            if (isAssignmentOperator(op)) {
                return evalAssignment(ctx);
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
            default -> throw new EvalException("Unsupported prefix operator.");
        };
    }

    private Value evalIncDec(MiniJavaParser.ExpressionContext target, boolean increment, boolean postfix) {
        VariableBinding variable = resolveVariable(extractAssignableName(target));
        if (!isIntegralType(variable.declaredType)) {
            throw new EvalException("++/-- requires int or char.");
        }

        Value original = variable.value;
        int delta = increment ? 1 : -1;
        int updated = original.asIntegral() + delta;
        Value updatedValue = castIntegralToTarget(variable.declaredType, updated);
        variable.value = updatedValue;
        return postfix ? original : updatedValue;
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

    private Value evalAssignment(MiniJavaParser.ExpressionContext ctx) {
        VariableBinding variable = resolveVariable(extractAssignableName(ctx.expression(0)));
        Value rhs = visit(ctx.expression(1));
        Value assigned = applyAssignment(variable.declaredType, variable.value, ctx.bop.getType(), rhs);
        variable.value = assigned;
        return assigned;
    }

    private Value applyAssignment(Value.Kind targetType, Value currentValue, int operator, Value rhs) {
        if (operator == MiniJavaParser.ASSIGN) {
            return applySimpleAssignment(targetType, rhs);
        }

        if (targetType == Value.Kind.STRING) {
            if (operator != MiniJavaParser.ADD_ASSIGN) {
                throw new EvalException("Only = and += are valid for string.");
            }
            return Value.ofString(currentValue.asString() + rhs.toConcatString());
        }

        if (targetType == Value.Kind.BOOLEAN) {
            if (rhs.kind() != Value.Kind.BOOLEAN) {
                throw new EvalException("Type mismatch for boolean assignment.");
            }

            boolean left = currentValue.asBoolean();
            boolean right = rhs.asBoolean();

            return switch (operator) {
                case MiniJavaParser.AND_ASSIGN -> Value.ofBoolean(left & right);
                case MiniJavaParser.OR_ASSIGN -> Value.ofBoolean(left | right);
                case MiniJavaParser.XOR_ASSIGN -> Value.ofBoolean(left ^ right);
                default -> throw new EvalException("Only =, &=, |=, ^= are valid for boolean.");
            };
        }

        if (!isIntegralType(targetType)) {
            throw new EvalException("Invalid assignment target type.");
        }

        int left = currentValue.asIntegral();
        int right = requireIntegral(rhs);

        int result = switch (operator) {
            case MiniJavaParser.ADD_ASSIGN -> left + right;
            case MiniJavaParser.SUB_ASSIGN -> left - right;
            case MiniJavaParser.MUL_ASSIGN -> left * right;
            case MiniJavaParser.DIV_ASSIGN -> {
                if (right == 0) {
                    throw new EvalException("Division by zero.");
                }
                yield left / right;
            }
            case MiniJavaParser.MOD_ASSIGN -> {
                if (right == 0) {
                    throw new EvalException("Modulo by zero.");
                }
                yield left % right;
            }
            case MiniJavaParser.AND_ASSIGN -> left & right;
            case MiniJavaParser.OR_ASSIGN -> left | right;
            case MiniJavaParser.XOR_ASSIGN -> left ^ right;
            case MiniJavaParser.LSHIFT_ASSIGN -> left << right;
            case MiniJavaParser.RSHIFT_ASSIGN -> left >> right;
            case MiniJavaParser.URSHIFT_ASSIGN -> left >>> right;
            default -> throw new EvalException("Unsupported assignment operator.");
        };

        return castIntegralToTarget(targetType, result);
    }

    private Value applySimpleAssignment(Value.Kind targetType, Value rhs) {
        if (targetType == Value.Kind.INT) {
            if (!rhs.isIntegral()) {
                throw new EvalException("Type mismatch for int assignment.");
            }
            return Value.ofInt(rhs.asIntegral());
        }

        if (targetType == Value.Kind.CHAR) {
            if (!rhs.isIntegral()) {
                throw new EvalException("Type mismatch for char assignment.");
            }
            return Value.ofChar(rhs.asIntegral());
        }

        if (targetType == Value.Kind.BOOLEAN) {
            if (rhs.kind() != Value.Kind.BOOLEAN) {
                throw new EvalException("Type mismatch for boolean assignment.");
            }
            return rhs;
        }

        if (targetType == Value.Kind.STRING) {
            if (rhs.kind() != Value.Kind.STRING) {
                throw new EvalException("Type mismatch for string assignment.");
            }
            return rhs;
        }

        throw new EvalException("Unsupported assignment target type.");
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
            case MiniJavaParser.BITAND -> evalBitAnd(left, right);
            case MiniJavaParser.CARET -> evalBitXor(left, right);
            case MiniJavaParser.BITOR -> evalBitOr(left, right);
            default -> throw new EvalException("Unsupported operator.");
        };
    }

    private Value evalBitAnd(Value left, Value right) {
        // if (left.kind() == Value.Kind.BOOLEAN && right.kind() == Value.Kind.BOOLEAN) {
        //     return Value.ofBoolean(left.asBoolean() & right.asBoolean());
        // }
        return Value.ofInt(requireIntegral(left) & requireIntegral(right));
    }

    private Value evalBitOr(Value left, Value right) {
        // if (left.kind() == Value.Kind.BOOLEAN && right.kind() == Value.Kind.BOOLEAN) {
        //     return Value.ofBoolean(left.asBoolean() | right.asBoolean());
        // }
        return Value.ofInt(requireIntegral(left) | requireIntegral(right));
    }

    private Value evalBitXor(Value left, Value right) {
        // if (left.kind() == Value.Kind.BOOLEAN && right.kind() == Value.Kind.BOOLEAN) {
        //     return Value.ofBoolean(left.asBoolean() ^ right.asBoolean());
        // }
        return Value.ofInt(requireIntegral(left) ^ requireIntegral(right));
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

    private int parseDecimalLiteral(String raw) {
        String normalized = raw.replace("_", "");
        if (normalized.endsWith("l") || normalized.endsWith("L")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        try {
            long parsed = Long.parseLong(normalized);
            if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
                throw new EvalException("Integer literal out of range: " + raw);
            }
            return (int) parsed;
        } catch (NumberFormatException ex) {
            throw new EvalException("Invalid integer literal: " + raw, ex);
        }
    }

    private String unescape(String text) {
        return text; // We don't have escaped char, e.g., '\n', in a string. A string can be empty, i.e., "".
        // 2.1 of Lab 1 - Expression Interpreter
    }

    private String extractAssignableName(MiniJavaParser.ExpressionContext expressionContext) {
        if (expressionContext.primary() != null && expressionContext.primary().identifier() != null) {
            return expressionContext.primary().identifier().getText();
        }
        throw new EvalException("Left-hand side must be an identifier.");
    }

    private void enterScope() {
        scopeStack.push(new ScopeFrame(scopeStack.size()));
    }

    private void exitScope() {
        ScopeFrame scope = scopeStack.pop();
        printScope(scope);
    }

    private void discardScope() {
        scopeStack.pop();
    }

    private void handleScopeExitOnException(RuntimeException ex) {
        if (ex instanceof BreakSignal || ex instanceof ContinueSignal) {
            exitScope();
            return;
        }
        discardScope();
    }

    private void printScope(ScopeFrame scope) {
        List<String> names = new ArrayList<>(scope.variables.keySet());
        Collections.sort(names);
        for (String name : names) {
            VariableBinding variable = scope.variables.get(name);
            System.out.println(
                "Scope " + scope.depth + ": " + name + ": (" + Value.kindName(variable.declaredType) + ") " + variable.value.displayString()
            );
        }
    }

    private ScopeFrame currentScope() {
        ScopeFrame scope = scopeStack.peek();
        if (scope == null) {
            throw new EvalException("No active scope.");
        }
        return scope;
    }

    private VariableBinding resolveVariable(String identifier) {
        for (ScopeFrame scope : scopeStack) {
            VariableBinding variable = scope.variables.get(identifier);
            if (variable != null) {
                return variable;
            }
        }
        throw new EvalException("Undeclared variable: " + identifier);
    }

    private Value.Kind parsePrimitiveType(String type) {
        return switch (type) {
            case "int" -> Value.Kind.INT;
            case "char" -> Value.Kind.CHAR;
            case "boolean" -> Value.Kind.BOOLEAN;
            case "string" -> Value.Kind.STRING;
            default -> throw new EvalException("Unsupported type: " + type);
        };
    }

    private Value defaultValueForType(Value.Kind kind) {
        return switch (kind) {
            case INT -> Value.ofInt(0);
            case CHAR -> Value.ofChar(0);
            case BOOLEAN -> Value.ofBoolean(false);
            case STRING -> Value.ofString("");
        };
    }

    private boolean isIntegralType(Value.Kind kind) {
        return kind == Value.Kind.INT || kind == Value.Kind.CHAR;
    }

    private Value castIntegralToTarget(Value.Kind targetType, int value) {
        return switch (targetType) {
            case INT -> Value.ofInt(value);
            case CHAR -> Value.ofChar(value);
            default -> throw new EvalException("Target type is not integral.");
        };
    }

    private boolean isAssignmentOperator(int op) {
        return op == MiniJavaParser.ASSIGN
            || op == MiniJavaParser.ADD_ASSIGN
            || op == MiniJavaParser.SUB_ASSIGN
            || op == MiniJavaParser.MUL_ASSIGN
            || op == MiniJavaParser.DIV_ASSIGN
            || op == MiniJavaParser.MOD_ASSIGN
            || op == MiniJavaParser.AND_ASSIGN
            || op == MiniJavaParser.OR_ASSIGN
            || op == MiniJavaParser.XOR_ASSIGN
            || op == MiniJavaParser.LSHIFT_ASSIGN
            || op == MiniJavaParser.RSHIFT_ASSIGN
            || op == MiniJavaParser.URSHIFT_ASSIGN;
    }

    private int requireIntegral(Value value) {
        return value.asIntegral();
    }

    private boolean requireBoolean(Value value) {
        return value.asBoolean();
    }
}
