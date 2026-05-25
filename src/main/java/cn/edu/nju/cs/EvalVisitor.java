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

    private static final class ReturnSignal extends RuntimeException {
        private final Value value;

        public ReturnSignal(Value value) {
            this.value = value;
        }
        
        public Value getValue() {
            return value;
        }
    }

    private static final class VariableBinding {
        final Value.Kind declaredType;
        final String typeName;
        Value value;

        private VariableBinding(Value.Kind declaredType, String typeName, Value value) {
            this.declaredType = declaredType;
            this.typeName = typeName;
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
    private final Map<String, List<MiniJavaParser.MethodDeclarationContext>> methods = new HashMap<>();
    private int loopDepth = 0;
    private String expectedArrayElementType = null; // for type-checking during array initialization

    @Override
    public Value visitCompilationUnit(MiniJavaParser.CompilationUnitContext ctx) {
        // Collect all methods
        for (MiniJavaParser.MethodDeclarationContext m : ctx.methodDeclaration()) {
            String name = m.identifier().getText();
            methods.computeIfAbsent(name, k -> new ArrayList<>()).add(m);
        }

        // Find entry method main
        List<MiniJavaParser.MethodDeclarationContext> mains = methods.getOrDefault("main", Collections.emptyList());
        MiniJavaParser.MethodDeclarationContext entryMethod = null;
        boolean ambiguous = false;

        for (MiniJavaParser.MethodDeclarationContext m : mains) {
            boolean isInt = m.typeType() != null && m.typeType().getText().equals("int");
            boolean isVoid = m.VOID() != null;
            int paramCount = (m.formalParameters().formalParameterList() != null) 
                    ? m.formalParameters().formalParameterList().formalParameter().size() 
                    : 0;

            if (paramCount == 0 && (isInt || isVoid)) {
                if (entryMethod != null) {
                    ambiguous = true;
                }
                entryMethod = m;
            }
        }

        if (ambiguous) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }
        
        if (entryMethod == null || entryMethod.VOID() != null || entryMethod.typeType() == null || !entryMethod.typeType().getText().equals("int")) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        try {
            // Use executeMethod for proper scope setup and return type checking
            Value mainRet = executeMethod(entryMethod, Collections.emptyList());
            // If executeMethod returns normally, the method body completed without error
            // (executeMethod already handles missing return by calling System.exit)
            if (mainRet != null && mainRet.kind() == Value.Kind.INT) {
                int exitCode = (int) mainRet.asIntegral();
                System.out.println("Process exits with " + exitCode + ".");
                System.exit(exitCode);
            }
            // Should not reach here - executeMethod handles errors internally
            System.out.println("Process exits with 34.");
            System.exit(34);
        } catch (EvalException ex) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

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
        if (ctx.VAR() != null) {
            // var declaration: type inferred from initializer
            String identifier = ctx.identifier().getText();
            Value value = visit(ctx.expression());
            if (value == null || value.kind() == Value.Kind.NULL) {
                // If null carries a known type (e.g. from method return), use it
                if (value != null && value.kind() == Value.Kind.NULL 
                    && !"null".equals(value.getTypeName())) {
                    String typeName = value.getTypeName();
                    Value.Kind declaredType = typeName.endsWith("[]") ? Value.Kind.ARRAY : parsePrimitiveType(typeName);
                    ScopeFrame scope = currentScope();
                    if (scope.variables.containsKey(identifier)) {
                        throw new EvalException("Variable already declared in current scope: " + identifier);
                    }
                    scope.variables.put(identifier, new VariableBinding(declaredType, typeName, value));
                    return null;
                }
                // If the expression is just a variable reference, infer from its declared type
                if (ctx.expression().primary() != null 
                    && ctx.expression().primary().identifier() != null) {
                    String refName = ctx.expression().primary().identifier().getText();
                    VariableBinding refVar = resolveVariable(refName);
                    String refTypeName = refVar.typeName;
                    Value.Kind refKind = refVar.declaredType;
                    ScopeFrame scope = currentScope();
                    if (scope.variables.containsKey(identifier)) {
                        throw new EvalException("Variable already declared in current scope: " + identifier);
                    }
                    scope.variables.put(identifier, new VariableBinding(refKind, refTypeName, value));
                    return null;
                }
                // If the expression is an array access, infer from the array's element type
                if (ctx.expression().LBRACK() != null && ctx.expression().expression().size() == 2) {
                    String arrTypeName = inferExpressionType(ctx.expression().expression(0));
                    if (arrTypeName != null && arrTypeName.endsWith("[]")) {
                        String elemTypeName = arrTypeName.substring(0, arrTypeName.length() - 2);
                        Value.Kind elemKind = elemTypeName.endsWith("[]") ? Value.Kind.ARRAY : parsePrimitiveType(elemTypeName);
                        ScopeFrame scope = currentScope();
                        if (scope.variables.containsKey(identifier)) {
                            throw new EvalException("Variable already declared in current scope: " + identifier);
                        }
                        scope.variables.put(identifier, new VariableBinding(elemKind, elemTypeName, value));
                        return null;
                    }
                }
                throw new EvalException("Cannot infer type for null");
            }
            if (value.kind() == Value.Kind.INT && value.isDecimalLiteral()) {
                // DECIMAL_LITERAL is inferred as int but should not keep literal status
                value = Value.ofInt(value.asIntegral());
            }
            String typeName;
            Value.Kind declaredType = value.kind();
            if (declaredType == Value.Kind.ARRAY) {
                typeName = value.getTypeName();
            } else {
                typeName = Value.kindName(declaredType);
            }
            ScopeFrame scope = currentScope();
            if (scope.variables.containsKey(identifier)) {
                throw new EvalException("Variable already declared in current scope: " + identifier);
            }
            scope.variables.put(identifier, new VariableBinding(declaredType, typeName, value));
        } else {
            // Typed declaration
            String typeStr = ctx.typeType().getText();
            Value.Kind declaredType;
            if (typeStr.endsWith("[]")) {
                declaredType = Value.Kind.ARRAY;
            } else {
                declaredType = parsePrimitiveType(typeStr);
            }

            MiniJavaParser.VariableDeclaratorContext varCtx = ctx.variableDeclarator();
            String identifier = varCtx.identifier().getText();
            ScopeFrame scope = currentScope();

            if (scope.variables.containsKey(identifier)) {
                throw new EvalException("Variable already declared in current scope: " + identifier);
            }

            Value value;
            if (varCtx.variableInitializer() == null) {
                value = defaultValueForTypeName(typeStr);
            } else {
                // Set expected element type for array initialization type-checking
                String savedExpectedType = this.expectedArrayElementType;
                if (typeStr.endsWith("[]")) {
                    this.expectedArrayElementType = typeStr.endsWith("[]") 
                        ? typeStr.substring(0, typeStr.length() - 2) : typeStr;
                }
                value = visit(varCtx.variableInitializer());
                this.expectedArrayElementType = savedExpectedType;
                value = applyTypeCheckedAssignment(typeStr, value);
            }

            scope.variables.put(identifier, new VariableBinding(declaredType, typeStr, value));
        }
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

        if (ctx.RETURN() != null) {
            Value retVal = null;
            if (ctx.expression() != null) {
                retVal = visit(ctx.expression());
            }
            throw new ReturnSignal(retVal);
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
        boolean hasImplicitScope = hasForImplicitScope(control);
        if (hasImplicitScope) {
            enterScope();
        }
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
            if (hasImplicitScope) {
                exitScope();
            }
            return null;
        } catch (RuntimeException ex) {
            if (hasImplicitScope) {
                handleScopeExitOnException(ex);
            }
            throw ex;
        } finally {
            loopDepth--;
        }
    }

    private boolean hasForImplicitScope(MiniJavaParser.ForControlContext control) {
        return control.forInit() != null && control.forInit().localVariableDeclaration() != null;
    }

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
    public Value visitMethodCall(MiniJavaParser.MethodCallContext ctx) {
        String methodName = ctx.identifier().getText();
        List<Value> args = new ArrayList<>();
        if (ctx.arguments().expressionList() != null) {
            for (MiniJavaParser.ExpressionContext argCtx : ctx.arguments().expressionList().expression()) {
                args.add(visit(argCtx));
            }
        }

        // Built-ins
        if (methodName.equals("print") && args.size() == 1) {
            System.out.print(args.get(0).displayString());
            return null;
        }
        if (methodName.equals("println")) {
            if (args.size() == 0) {
                System.out.println();
            } else if (args.size() == 1) {
                System.out.println(args.get(0).displayString());
            } else {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            return null;
        }
        if (methodName.equals("assert") && args.size() == 1) {
            if (args.get(0).kind() != Value.Kind.BOOLEAN) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            if (!args.get(0).asBoolean()) {
                System.out.println("Process exits with 33.");
                System.exit(33);
            }
            return null;
        }
        if (methodName.equals("length") && args.size() == 1) {
            Value arg = args.get(0);
            if (arg.kind() == Value.Kind.NULL) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            if (arg.kind() == Value.Kind.ARRAY) {
                Value[] arr = (Value[]) arg.getValue();
                return Value.ofInt(arr.length);
            }
            if (arg.kind() == Value.Kind.STRING) {
                return Value.ofInt(arg.asString().length());
            }
            System.out.println("Process exits with 34.");
            System.exit(34);
        }
        if (methodName.equals("to_char_array") && args.size() == 1) {
            Value arg = args.get(0);
            if (arg.kind() != Value.Kind.STRING || arg.getValue() == null) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            String s = arg.asString();
            Value[] chars = new Value[s.length()];
            for (int i = 0; i < s.length(); i++) {
                chars[i] = Value.ofChar(s.charAt(i));
            }
            return Value.ofArray(chars, "char[]");
        }
        if (methodName.equals("to_string") && args.size() == 1) {
            Value arg = args.get(0);
            if (arg.kind() == Value.Kind.NULL) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            if (arg.kind() != Value.Kind.ARRAY || !arg.getTypeName().equals("char[]")) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            Value[] arr = (Value[]) arg.getValue();
            StringBuilder sb = new StringBuilder();
            for (Value v : arr) {
                sb.append((char) v.asIntegral());
            }
            return Value.ofString(sb.toString());
        }
        if (methodName.equals("atoi") && args.size() == 1) {
            Value arg = args.get(0);
            if (arg.kind() != Value.Kind.STRING) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            try {
                return Value.ofInt(Integer.parseInt(arg.asString()));
            } catch (NumberFormatException e) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
        }
        if (methodName.equals("itoa") && args.size() == 1) {
            Value arg = args.get(0);
            if (arg.kind() == Value.Kind.CHAR) {
                arg = Value.ofInt(arg.asIntegral()); // implicit conversion
            }
            if (arg.kind() != Value.Kind.INT) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            return Value.ofString(Integer.toString((Integer) arg.getValue()));
        }

        // User defined methods
        List<MiniJavaParser.MethodDeclarationContext> cands = methods.getOrDefault(methodName, Collections.emptyList());
        MiniJavaParser.MethodDeclarationContext best = null;
        int bestCost = Integer.MAX_VALUE;
        boolean ambiguous = false;

        for (MiniJavaParser.MethodDeclarationContext m : cands) {
            List<MiniJavaParser.FormalParameterContext> params = 
                (m.formalParameters().formalParameterList() != null) 
                ? m.formalParameters().formalParameterList().formalParameter() 
                : Collections.emptyList();
            
            if (params.size() != args.size()) continue;

            int currentCost = 0;
            boolean compatible = true;
            for (int i = 0; i < args.size(); i++) {
                String paramType = params.get(i).typeType().getText();
                int cost = getConversionCost(args.get(i), paramType);
                if (cost == -1) {
                    compatible = false;
                    break;
                }
                currentCost += cost;
            }

            if (compatible) {
                if (currentCost < bestCost) {
                    bestCost = currentCost;
                    best = m;
                    ambiguous = false;
                } else if (currentCost == bestCost) {
                    ambiguous = true;
                }
            }
        }

        if (best == null || ambiguous) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        return executeMethod(best, args);
    }

    private int getConversionCost(Value arg, String formalParamType) {
        // Used for method argument matching: int→char is NOT allowed
        String argType = arg.getTypeName();
        if (argType.equals(formalParamType)) return 0;
        if (argType.equals("char") && formalParamType.equals("int")) return 1;
        if (argType.equals("null") && formalParamType.endsWith("[]")) return 1;
        return -1;
    }

    private int getReturnConversionCost(Value ret, String returnType) {
        // Used for return type checking: literal int→char IS allowed
        String retTypeName = ret.getTypeName();
        if (retTypeName.equals(returnType)) return 0;
        if (retTypeName.equals("char") && returnType.equals("int")) return 1;
        if (retTypeName.equals("null") && returnType.endsWith("[]")) return 1;
        if (retTypeName.equals("int") && returnType.equals("char") && ret.isDecimalLiteral()) return 1;
        return -1;
    }

    private Value executeMethod(MiniJavaParser.MethodDeclarationContext m, List<Value> args) {
        Deque<ScopeFrame> savedStack = new ArrayDeque<>(scopeStack);
        scopeStack.clear();
        enterScope();

        List<MiniJavaParser.FormalParameterContext> params = 
            (m.formalParameters().formalParameterList() != null) 
            ? m.formalParameters().formalParameterList().formalParameter() 
            : Collections.emptyList();

        for (int i = 0; i < params.size(); i++) {
            String pName = params.get(i).identifier().getText();
            String pTypeStr = params.get(i).typeType().getText();
            Value argVal = args.get(i);
            
            if (argVal.getTypeName().equals("char") && pTypeStr.equals("int")) {
                argVal = Value.ofInt(argVal.asIntegral());
            } else if (argVal.getTypeName().equals("null") && pTypeStr.endsWith("[]")) {
                argVal = Value.ofNull(); 
            }
            // Strip isDecimalLiteral: method parameters are not literals
            if (argVal.isDecimalLiteral()) {
                argVal = Value.ofInt(argVal.asIntegral());
            }
            currentScope().variables.put(pName, new VariableBinding(argVal.kind(), pTypeStr, argVal));
        }

        Value ret = null;
        try {
            visit(m.block());
        } catch (ReturnSignal sig) {
            ret = sig.getValue();
        } catch (EvalException ex) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        scopeStack.clear();
        scopeStack.addAll(savedStack);

        boolean isVoid = m.VOID() != null;
        if (isVoid) {
            if (ret != null) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            return null;
        } else {
            if (ret == null) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            String retType = m.typeType().getText();
            int cost = getReturnConversionCost(ret, retType);
            if (cost == -1) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            if (cost == 1) { 
                if (retType.equals("int") && ret.getTypeName().equals("char")) {
                    ret = Value.ofInt(ret.asIntegral());
                } else if (retType.equals("char") && ret.getTypeName().equals("int")) {
                    ret = Value.ofChar(ret.asIntegral());
                } else if (retType.endsWith("[]") && ret.getTypeName().equals("null")) {
                    ret = Value.ofNullTyped(retType); 
                }
            }
            // Strip isDecimalLiteral: return values lose their literal status
            if (ret.isDecimalLiteral()) {
                ret = Value.ofInt(ret.asIntegral());
            }
            return ret;
        }
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
            int val = parseDecimalLiteral(ctx.DECIMAL_LITERAL().getText());
            // DECIMAL_LITERAL in char range can be treated as char in assignment/return contexts
            if (val >= -128 && val <= 127) {
                return Value.ofDecimalLiteral(val);
            }
            return Value.ofInt(val);
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

        if (ctx.NULL_LITERAL() != null) {
            return Value.ofNull();
        }

        throw new EvalException("Unknown literal node.");
    }

    @Override
    public Value visitExpression(MiniJavaParser.ExpressionContext ctx) {
        if (ctx.primary() != null) {
            return visit(ctx.primary());
        }

        if (ctx.methodCall() != null) {
            return visit(ctx.methodCall());
        }

        // Array access: arr[idx]
        if (ctx.LBRACK() != null && ctx.expression().size() == 2) {
            return evalArrayAccess(ctx);
        }

        // NEW creator: new int[5], new int[]{1,2,3}
        if (ctx.NEW() != null) {
            return visit(ctx.creator());
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

        if (ctx.typeType() != null) {
            Value operand = visit(ctx.expression(0));
            return evalCast(ctx.typeType().getText(), operand);
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
        // Array element inc/dec: arr[i]++ or ++arr[i]
        if (target.LBRACK() != null && target.expression().size() == 2) {
            Value arr = visit(target.expression(0));
            Value idx = visit(target.expression(1));
            if (arr.kind() != Value.Kind.ARRAY || arr.getValue() == null) {
                throw new EvalException("Null pointer error.");
            }
            Value[] elements = (Value[]) arr.getValue();
            int i = idx.asIntegral();
            if (i < 0 || i >= elements.length) {
                throw new EvalException("Array out-of-bounds error.");
            }
            Value elem = elements[i];
            if (elem == null || !elem.isIntegral()) {
                throw new EvalException("++/-- requires int or char.");
            }
            int original = elem.asIntegral();
            int updated = original + (increment ? 1 : -1);
            elements[i] = (elem.kind() == Value.Kind.CHAR) 
                ? Value.ofChar(updated) : Value.ofInt(updated);
            return postfix ? (elem.kind() == Value.Kind.CHAR ? Value.ofChar(original) : Value.ofInt(original))
                           : (elem.kind() == Value.Kind.CHAR ? Value.ofChar(updated) : Value.ofInt(updated));
        }

        // Simple variable inc/dec
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

    private Value evalArrayAccess(MiniJavaParser.ExpressionContext ctx) {
        Value arr = visit(ctx.expression(0));
        Value idx = visit(ctx.expression(1));
        if (arr.kind() != Value.Kind.ARRAY || arr.getValue() == null) {
            throw new EvalException("Null pointer error.");
        }
        Value[] elements = (Value[]) arr.getValue();
        int i = idx.asIntegral();
        if (i < 0 || i >= elements.length) {
            throw new EvalException("Array out-of-bounds error.");
        }
        Value elem = elements[i];
        return elem != null ? elem : Value.ofNull();
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
        MiniJavaParser.ExpressionContext left = ctx.expression(0);
        Value rhs = visit(ctx.expression(1));

        // Array element assignment: arr[idx] = rhs or arr[idx] += rhs
        if (left.expression() != null && left.expression().size() == 2
            && left.bop == null && left.getText().contains("[")) {
            Value arr = visit(left.expression(0));
            Value idx = visit(left.expression(1));
            if (arr.kind() != Value.Kind.ARRAY || arr.getValue() == null) {
                throw new EvalException("Null pointer error.");
            }
            Value[] elements = (Value[]) arr.getValue();
            int i = idx.asIntegral();
            if (i < 0 || i >= elements.length) {
                throw new EvalException("Array out-of-bounds error.");
            }
            Value currentVal = elements[i];
            if (currentVal == null) currentVal = Value.ofNull();

            String innerTypeName = arr.getTypeName();
            if (innerTypeName.endsWith("[]")) {
                innerTypeName = innerTypeName.substring(0, innerTypeName.length() - 2);
            }

            if (rhs.kind() == Value.Kind.ARRAY) {
                // Validate array type compatibility
                rhs = applyTypeCheckedAssignment(innerTypeName, rhs);
            }

            Value.Kind elemKind = Value.Kind.INT;
            if (innerTypeName.startsWith("char")) elemKind = Value.Kind.CHAR;
            else if (innerTypeName.startsWith("boolean")) elemKind = Value.Kind.BOOLEAN;
            else if (innerTypeName.startsWith("string")) elemKind = Value.Kind.STRING;
            else if (innerTypeName.contains("[]")) elemKind = Value.Kind.ARRAY;

            Value assigned = applyAssignment(elemKind, currentVal, ctx.bop.getType(), rhs);
            elements[i] = assigned;
            return assigned;
        }

        // Simple variable assignment
        VariableBinding variable = resolveVariable(extractAssignableName(left));
        if (rhs.kind() == Value.Kind.ARRAY && variable.typeName.endsWith("[]")) {
            // Validate array type compatibility before assignment
            rhs = applyTypeCheckedAssignment(variable.typeName, rhs);
        } else if (rhs.kind() == Value.Kind.NULL && variable.typeName.endsWith("[]")) {
            rhs = Value.ofNull();
        }
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
            // Only allow int→char if it's a DECIMAL_LITERAL (non-literal ints cannot become char)
            if (rhs.kind() == Value.Kind.INT && !rhs.isDecimalLiteral()) {
                throw new EvalException("Type mismatch: cannot assign non-literal int to char.");
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

        if (targetType == Value.Kind.ARRAY || targetType == Value.Kind.NULL) {
            // For array assignment, rhs must be array of compatible type or null
            if (rhs.kind() == Value.Kind.NULL) {
                return rhs; // null assignable to any array
            }
            if (rhs.kind() == Value.Kind.ARRAY) {
                return rhs;
            }
            throw new EvalException("Type mismatch for array assignment.");
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
        } else if (left.kind() == Value.Kind.ARRAY && right.kind() == Value.Kind.ARRAY) {
            // Reference equality for arrays
            result = left.getValue() == right.getValue();
        } else if ((left.kind() == Value.Kind.ARRAY && right.kind() == Value.Kind.NULL)
                || (left.kind() == Value.Kind.NULL && right.kind() == Value.Kind.ARRAY)) {
            // array == null or null == array: check if array reference is null
            Value arr = left.kind() == Value.Kind.ARRAY ? left : right;
            result = arr.getValue() == null;
        } else if (left.kind() == Value.Kind.NULL && right.kind() == Value.Kind.NULL) {
            result = true;
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
        scopeStack.pop();
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

    /** Infer the type of an expression without side effects (for var null inference) */
    private String inferExpressionType(MiniJavaParser.ExpressionContext ctx) {
        if (ctx.primary() != null && ctx.primary().identifier() != null) {
            VariableBinding vb = resolveVariable(ctx.primary().identifier().getText());
            return vb.typeName;
        }
        if (ctx.LBRACK() != null && ctx.expression().size() == 2) {
            String arrType = inferExpressionType(ctx.expression(0));
            if (arrType != null && arrType.endsWith("[]")) {
                return arrType.substring(0, arrType.length() - 2);
            }
        }
        return null;
    }

    private Value defaultValueForType(Value.Kind kind) {
        return switch (kind) {
            case INT -> Value.ofInt(0);
            case CHAR -> Value.ofChar(0);
            case BOOLEAN -> Value.ofBoolean(false);
            case STRING -> Value.ofString("");
            case ARRAY -> Value.ofNull();
            case NULL -> Value.ofNull();
        };
    }

    private Value defaultValueForTypeName(String typeName) {
        if (typeName.endsWith("[]")) return Value.ofNull();
        return defaultValueForType(parsePrimitiveType(typeName));
    }

    private Value applyTypeCheckedAssignment(String typeName, Value rhs) {
        String rhsType = rhs.getTypeName();

        if ("int".equals(typeName)) {
            if (rhs.isIntegral()) {
                return Value.ofInt(rhs.asIntegral());
            }
        }

        if ("char".equals(typeName)) {
            if (rhsType.equals("char")) {
                return rhs;
            }
            // char = int (only allowed for DECIMAL_LITERAL in char range)
            if (rhsType.equals("int") && rhs.isDecimalLiteral()) {
                return Value.ofChar(rhs.asIntegral());
            }
        }

        // Same type → OK (non-integral, non-char)
        if (rhsType.equals(typeName)) return rhs;

        // null → any array type
        if (rhsType.equals("null") && typeName.endsWith("[]")) return Value.ofNull();

        // Array assignment: types must match exactly (no cross-type array assignment)
        if (rhs.kind() == Value.Kind.ARRAY && typeName.endsWith("[]")) {
            // For array-to-array assignment, require same type name (unless rhs is untyped initializer)
            if (!rhsType.isEmpty() && !rhsType.equals(typeName)) {
                throw new EvalException("Type mismatch: cannot assign " + rhsType + " to " + typeName);
            }
            Value validated = validateArrayElements(typeName, rhs);
            return Value.rewrapArrayType(validated, typeName);
        }

        throw new EvalException("Type mismatch: cannot assign " + rhsType + " to " + typeName);
    }

    private Value validateArrayElements(String arrayTypeName, Value arrayVal) {
        if (arrayVal.kind() != Value.Kind.ARRAY) return arrayVal;
        Value[] elements = (Value[]) arrayVal.getValue();
        String innerTypeName = arrayTypeName.endsWith("[]") 
            ? arrayTypeName.substring(0, arrayTypeName.length() - 2) : arrayTypeName;

        for (int i = 0; i < elements.length; i++) {
            Value el = elements[i];
            if (el == null) {
                elements[i] = Value.ofNull();
            } else if (el.kind() == Value.Kind.ARRAY) {
                // Recursively validate nested arrays
                elements[i] = validateArrayElements(innerTypeName, el);
            } else if (el.kind() == Value.Kind.NULL) {
                // null is compatible with any array type (when inner type is also array)
                if (!innerTypeName.endsWith("[]")) {
                    throw new EvalException("Type mismatch: null is not compatible with " + innerTypeName);
                }
            } else if (el.kind() == Value.Kind.CHAR && innerTypeName.equals("int")) {
                // char→int is allowed in array initialization context
                elements[i] = Value.ofInt(el.asIntegral());
            } else {
                // Primitive element: validate against inner type
                elements[i] = applyTypeCheckedAssignment(innerTypeName, el);
            }
        }
        return arrayVal;
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

    @Override
    public Value visitVariableInitializer(MiniJavaParser.VariableInitializerContext ctx) {
        if (ctx.arrayInitializer() != null) {
            return visitArrayInitializer(ctx.arrayInitializer());
        }
        return visit(ctx.expression());
    }

    @Override
    public Value visitArrayInitializer(MiniJavaParser.ArrayInitializerContext ctx) {
        if (ctx.variableInitializer() == null || ctx.variableInitializer().isEmpty()) {
            return Value.ofArray(new Value[0], "");
        }
        Value[] elements = new Value[ctx.variableInitializer().size()];
        String myElementType = this.expectedArrayElementType;
        for (int i = 0; i < ctx.variableInitializer().size(); i++) {
            // For nested arrays, set the expected element type for the inner initializer
            String savedExpectedType = this.expectedArrayElementType;
            if (myElementType != null && myElementType.endsWith("[]")) {
                this.expectedArrayElementType = myElementType.substring(0, myElementType.length() - 2);
            } else {
                this.expectedArrayElementType = myElementType;
            }
            elements[i] = visit(ctx.variableInitializer(i));
            this.expectedArrayElementType = savedExpectedType;
            
            // Type-check the element immediately
            if (myElementType != null && elements[i] != null) {
                elements[i] = applyTypeCheckedAssignment(myElementType, elements[i]);
            }
        }
        return Value.ofArray(elements, "");
    }

    @Override
    public Value visitCreator(MiniJavaParser.CreatorContext ctx) {
        String baseType = ctx.createdName().getText();
        MiniJavaParser.ArrayCreatorRestContext rest = ctx.arrayCreatorRest();
        if (rest.arrayInitializer() != null) {
            Value init = visit(rest.arrayInitializer());
            // Count dimensions from '[' ']' pairs, but only those BEFORE the array initializer.
            // Do NOT count '[' inside char/string literals within the initializer.
            int dimensions = countArrayDimensions(rest.getText());
            StringBuilder typeStr = new StringBuilder(baseType);
            for (int i = 0; i < dimensions; i++) typeStr.append("[]");
            return Value.rewrapArrayType(init, typeStr.toString());
        }

        // e.g. new int[5][10][]
        int allocatedDimensions = rest.expression().size();
        
        int totalDims = countArrayDimensions(rest.getText());
        
        StringBuilder typeName = new StringBuilder(baseType);
        for(int i=0; i<totalDims; i++) typeName.append("[]");
        
        int[] dims = new int[allocatedDimensions];
        for (int i = 0; i < allocatedDimensions; i++) {
            dims[i] = visit(rest.expression(i)).asIntegral();
        }
        
        return createMultiArray(dims, 0, typeName.toString(), baseType, totalDims);
    }

    /** Count array dimensions from bracket pairs, ignoring brackets inside char/string literals. */
    private int countArrayDimensions(String text) {
        int dimensions = 0;
        boolean inCharLiteral = false;
        boolean inStringLiteral = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStringLiteral) {
                if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                    inStringLiteral = false;
                }
                continue;
            }
            if (inCharLiteral) {
                if (c == '\'' && (i == 0 || text.charAt(i - 1) != '\\')) {
                    inCharLiteral = false;
                }
                continue;
            }
            if (c == '"') {
                inStringLiteral = true;
                continue;
            }
            if (c == '\'') {
                inCharLiteral = true;
                continue;
            }
            if (c == '[') {
                dimensions++;
            }
        }
        return dimensions;
    }

    private Value createMultiArray(int[] dims, int dimIndex, String typeName, String baseType, int totalDims) {
        if (dimIndex >= dims.length) {
            // Unallocated dimension, return null (in java it's initially null)
            return Value.ofNull(); 
        }
        int length = dims[dimIndex];
        Value[] arr = new Value[length];
        
        String innerTypeName = typeName.substring(0, typeName.length() - 2);
        
        if (dimIndex == dims.length - 1 && dims.length == totalDims) {
            // Leaf array, fill with default values
            for (int i = 0; i < length; i++) {
                arr[i] = defaultValueForType(getKindFromString(baseType));
            }
        } else {
            // Recursive multi dimensional array allocation if dims has more
            if (dimIndex < dims.length - 1) {
                for (int i = 0; i < length; i++) {
                    arr[i] = createMultiArray(dims, dimIndex + 1, innerTypeName, baseType, totalDims);
                }
            } else {
                // Dim index matches allocated length but not total dims (like new int[5][]), leave as null
                for (int i = 0; i < length; i++) {
                    arr[i] = Value.ofNull();
                }
            }
        }
        return Value.ofArray(arr, typeName);
    }

    private Value.Kind getKindFromString(String typeStr) {
        return switch (typeStr) {
            case "int" -> Value.Kind.INT;
            case "boolean" -> Value.Kind.BOOLEAN;
            case "char" -> Value.Kind.CHAR;
            case "String", "string" -> Value.Kind.STRING;
            default -> throw new EvalException("Unknown kind: " + typeStr);
        };
    }
}
