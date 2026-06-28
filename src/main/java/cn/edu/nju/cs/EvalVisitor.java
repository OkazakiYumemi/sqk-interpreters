package cn.edu.nju.cs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // ---- Lab 4: Class-related data structures ----

    private static final class FieldInfo {
        final String name;
        final String typeName;
        final boolean hasInitializer;
        final MiniJavaParser.VariableInitializerContext initializerCtx;

        FieldInfo(String name, String typeName, boolean hasInitializer,
                  MiniJavaParser.VariableInitializerContext initializerCtx) {
            this.name = name;
            this.typeName = typeName;
            this.hasInitializer = hasInitializer;
            this.initializerCtx = initializerCtx;
        }
    }

    private static final class ClassInfo {
        final String name;
        String parentName; // null if no parent (no extends)
        final Map<String, FieldInfo> fields = new LinkedHashMap<>(); // preserve declaration order
        final Map<String, List<MiniJavaParser.MethodDeclarationContext>> methods = new HashMap<>();
        final List<MiniJavaParser.ConstructorDeclarationContext> constructors = new ArrayList<>();
        boolean hasExplicitConstructor = false;

        ClassInfo(String name) {
            this.name = name;
        }

        /** Walk up the inheritance chain (including this class) to find the first class that declares field. */
        FieldInfo findField(String fieldName) {
            FieldInfo fi = fields.get(fieldName);
            if (fi != null) return fi;
            return null; // caller should walk superclass chain
        }

        /** Get all method candidates for a given name from this class only. */
        List<MiniJavaParser.MethodDeclarationContext> getLocalMethods(String methodName) {
            return methods.getOrDefault(methodName, Collections.emptyList());
        }
    }

    private final Deque<ScopeFrame> scopeStack = new ArrayDeque<>();
    private final Map<String, List<MiniJavaParser.MethodDeclarationContext>> methods = new HashMap<>();
    private final Map<String, ClassInfo> classes = new LinkedHashMap<>(); // preserve declaration order
    private ClassInfo currentClass = null; // class being parsed during registration
    private int loopDepth = 0;
    private String expectedArrayElementType = null; // for type-checking during array initialization

    @Override
    public Value visitCompilationUnit(MiniJavaParser.CompilationUnitContext ctx) {
        // ---- Phase 1: Collect class declarations ----
        for (MiniJavaParser.ClassDeclarationContext cd : ctx.classDeclaration()) {
            String className = cd.identifier().getText();
            if (classes.containsKey(className)) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            ClassInfo ci = new ClassInfo(className);
            if (cd.parentClassDeclaration() != null) {
                ci.parentName = cd.parentClassDeclaration().identifier().getText();
            }
            classes.put(className, ci);
        }

        // ---- Phase 2: Validate inheritance & parse class bodies ----
        for (MiniJavaParser.ClassDeclarationContext cd : ctx.classDeclaration()) {
            String className = cd.identifier().getText();
            ClassInfo ci = classes.get(className);

            // Validate parent class exists
            if (ci.parentName != null && !classes.containsKey(ci.parentName)) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }

            // Check for inheritance cycles
            if (hasCyclicInheritance(className)) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }

            // Parse class body: collect fields, methods, constructors
            currentClass = ci;
            visitClassBody(cd.classBody());
            currentClass = null;
        }

        // ---- Phase 3: Collect top-level methods ----
        for (MiniJavaParser.MethodDeclarationContext m : ctx.methodDeclaration()) {
            String name = m.identifier().getText();
            methods.computeIfAbsent(name, k -> new ArrayList<>()).add(m);
        }

        // ---- Phase 4: Find entry method main ----
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

        // ---- Phase 5: Execute main ----
        try {
            Value mainRet = executeMethod(entryMethod, Collections.emptyList());
            if (mainRet != null && mainRet.kind() == Value.Kind.INT) {
                int exitCode = (int) mainRet.asIntegral();
                System.out.println("Process exits with " + exitCode + ".");
                System.exit(exitCode);
            }
            System.out.println("Process exits with 34.");
            System.exit(34);
        } catch (EvalException ex) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        return null;
    }

    /** Check for cyclic inheritance starting from the given class. */
    private boolean hasCyclicInheritance(String className) {
        Set<String> visited = new HashSet<>();
        String current = className;
        while (current != null) {
            if (!visited.add(current)) return true; // cycle detected
            ClassInfo ci = classes.get(current);
            if (ci == null) break;
            current = ci.parentName;
        }
        return false;
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
            if (declaredType == Value.Kind.ARRAY || declaredType == Value.Kind.CLASS) {
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
            Value.Kind declaredType = resolveTypeKind(typeStr);

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

        // Unqualified method call: try class method on this first (before built-ins!)
        if (currentObject != null && enclosingClass != null) {
            Value result = tryResolveThisMethod(methodName, args);
            if (result != null) return (result.kind() == Value.Kind.INT && result.asIntegral() == Integer.MIN_VALUE) ? null : result;
        }

        // Built-ins
        if (methodName.equals("print") && args.size() == 1) {
            System.out.print(getDisplayString(args.get(0)));
            System.out.flush();
            return null;
        }
        if (methodName.equals("println")) {
            if (args.size() == 0) {
                System.out.println();
            } else if (args.size() == 1) {
                System.out.println(getDisplayString(args.get(0)));
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

        // Fall back to top-level methods
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
        if (argType.equals("null") && classes.containsKey(formalParamType)) return 1;
        // Class upcast: subclass → superclass (flat cost = 1 per spec)
        if (arg.kind() == Value.Kind.CLASS && classes.containsKey(argType)
            && classes.containsKey(formalParamType)) {
            int dist = getInheritanceDistance(argType, formalParamType);
            if (dist >= 0) return 1; // all upcasts cost 1, not depth
        }
        return -1;
    }

    private int getReturnConversionCost(Value ret, String returnType) {
        // Used for return type checking: literal int→char IS allowed
        String retTypeName = ret.getTypeName();
        if (retTypeName.equals(returnType)) return 0;
        if (retTypeName.equals("char") && returnType.equals("int")) return 1;
        if (retTypeName.equals("null") && returnType.endsWith("[]")) return 1;
        if (retTypeName.equals("null") && classes.containsKey(returnType)) return 1;
        if (retTypeName.equals("int") && returnType.equals("char") && ret.isDecimalLiteral()) return 1;
        // Class upcast: subclass → superclass (for return type)
        if (ret.kind() == Value.Kind.CLASS && classes.containsKey(retTypeName)
            && classes.containsKey(returnType)) {
            int dist = getInheritanceDistance(retTypeName, returnType);
            if (dist >= 0) return dist;
        }
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
            // Upcast class argument to declared parameter type (for proper overload resolution)
            if (argVal.kind() == Value.Kind.CLASS && classes.containsKey(pTypeStr)
                && !argVal.getTypeName().equals(pTypeStr)) {
                argVal = Value.ofClassObjWithType(argVal.asClassObj(), pTypeStr);
            }
            Value.Kind declaredKind = resolveTypeKind(pTypeStr);
            currentScope().variables.put(pName, new VariableBinding(declaredKind, pTypeStr, argVal));
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
            String retType = m.typeType().getText();
            // null return is valid for class types and array types
            if (ret == null) {
                if (retType.endsWith("[]") || classes.containsKey(retType)) {
                    ret = Value.ofNullTyped(retType);
                } else {
                    System.out.println("Process exits with 34.");
                    System.exit(34);
                }
            }
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
                } else if (classes.containsKey(retType) && ret.getTypeName().equals("null")) {
                    ret = Value.ofNullTyped(retType);
                } else if (classes.containsKey(retType) && ret.kind() == Value.Kind.CLASS) {
                    // Subclass → superclass return: re-wrap with correct declared type
                    ret = Value.ofClassObjWithType(ret.asClassObj(), retType);
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

        // Handle THIS keyword: return current object reference
        if (ctx.THIS() != null) {
            if (currentObject == null) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            return Value.ofClassObj(currentObject);
        }

        // Handle SUPER keyword (as primary); actual resolution happens in DOT handler.
        // SUPER alone (without .x or .(...)) is an error, but we return currentObject
        // so the DOT/methodCall handler can detect it.
        if (ctx.SUPER() != null) {
            if (currentObject == null) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            return Value.ofClassObj(currentObject);
        }

        if (ctx.identifier() != null) {
            String name = ctx.identifier().getText();
            // First try local scope resolution
            for (ScopeFrame scope : scopeStack) {
                VariableBinding variable = scope.variables.get(name);
                if (variable != null) {
                    return variable.value;
                }
            }
            // Not found in local scope: if inside a class method, try this.x
            if (currentObject != null) {
                return resolveFieldOnObject(currentObject, name, enclosingClass.name);
            }
            throw new EvalException("Undeclared variable: " + name);
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
        // NOTE: Order matters! DOT expressions (a.foo, a.foo()) contain
        // methodCall/identifier children, so check bop/DOT BEFORE standalone methodCall/primary.

        // ---- DOT operator: field access or method call (must be before primary/methodCall) ----
        if (ctx.bop != null && ctx.bop.getType() == MiniJavaParser.DOT) {
            return evalDotExpression(ctx);
        }

        // ---- instanceof operator (must be before primary) ----
        if (ctx.INSTANCEOF() != null) {
            return evalInstanceOf(ctx);
        }

        if (ctx.primary() != null) {
            return visit(ctx.primary());
        }

        // Standalone methodCall (not part of a DOT expression)
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

        // Check if target is a simple identifier that might be a field on this
        if (target.primary() != null && target.primary().identifier() != null) {
            String name = target.primary().identifier().getText();
            // Try local variable first
            for (ScopeFrame scope : scopeStack) {
                VariableBinding variable = scope.variables.get(name);
                if (variable != null) {
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
            }
            // Not a local variable — try field on this
            if (currentObject != null && enclosingClass != null) {
                ClassInfo declClass = findFieldDeclaringClass(enclosingClass.name, name);
                if (declClass != null) {
                    FieldInfo fi = declClass.fields.get(name);
                    String fkey = declClass.name + "." + name;
                    Value original = currentObject.fields.get(fkey);
                    if (original == null) { original = currentObject.fields.get(name); } // fallback
                    if (original == null) original = defaultValueForTypeName(fi.typeName);
                    if (!original.isIntegral()) {
                        throw new EvalException("++/-- requires int or char.");
                    }
                    int delta = increment ? 1 : -1;
                    int updated = original.asIntegral() + delta;
                    Value.Kind targetKind = resolveTypeKind(fi.typeName);
                    Value updatedValue = castIntegralToTarget(targetKind, updated);
                    currentObject.fields.put(fkey, updatedValue);
                    return postfix ? original : updatedValue;
                }
            }
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
        // --- Primitive casts ---
        if ("int".equals(typeName)) {
            return Value.ofInt(requireIntegral(operand));
        }

        if ("char".equals(typeName)) {
            return Value.ofChar(requireIntegral(operand));
        }

        // --- Class type casts ---
        if (classes.containsKey(typeName)) {
            // (C) null → always null
            if (operand.kind() == Value.Kind.NULL) {
                return Value.ofNull();
            }

            // Operand must be a class type
            if (operand.kind() != Value.Kind.CLASS) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }

            ObjectInstance obj = operand.asClassObj();
            if (obj == null) {
                return Value.ofNull(); // null is always valid
            }

            String realType = obj.className;

            // Check that the declared type and target type are in the same inheritance tree
            String declType = operand.getTypeName();
            if (!isSameInheritanceTree(declType, typeName)) {
                // Unrelated types → error
                System.out.println("Process exits with 34.");
                System.exit(34);
            }

            // Upcast: (Super) subExpr → always legal
            // Downcast: (Sub) superExpr → legal only if real(expr) is Sub or subclass of Sub
            int dist = getInheritanceDistance(realType, typeName);
            if (dist < 0) {
                // real type is NOT typeName or its subclass → downcast fails
                System.out.println("Process exits with 34.");
                System.exit(34);
            }

            // Cast succeeds — return the object with the target declared type
            return Value.ofClassObjWithType(obj, typeName);
        }

        throw new EvalException("Unsupported cast type: " + typeName);
    }

    private Value evalInstanceOf(MiniJavaParser.ExpressionContext ctx) {
        Value operand = visit(ctx.expression(0));
        String targetTypeName = ctx.typeType().getText();

        // Static checks: both must be class types in the same inheritance tree
        // decl(obj) must be a class type
        if (operand.kind() == Value.Kind.NULL) {
            // null instanceof C → false (null is not an instance of any class)
            return Value.ofBoolean(false);
        }
        if (operand.kind() != Value.Kind.CLASS) {
            // e.g., arr instanceof C where arr is int[] → type error
            System.out.println("Process exits with 34.");
            System.exit(34);
        }
        if (!classes.containsKey(targetTypeName)) {
            // TargetType must be a class type (e.g., obj instanceof int[] → type error)
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        String declType = operand.getTypeName();
        if (!isSameInheritanceTree(declType, targetTypeName)) {
            // Unrelated class types → type error
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        // Runtime check: real(obj) is TargetType or a subclass of TargetType
        ObjectInstance obj = operand.asClassObj();
        if (obj == null) {
            return Value.ofBoolean(false);
        }
        String realType = obj.className;
        int dist = getInheritanceDistance(realType, targetTypeName);
        return Value.ofBoolean(dist >= 0);
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

        // For compound assignments to simple variables, snapshot current value
        // BEFORE evaluating RHS (RHS may modify the same variable, e.g. a += (a = 3))
        Value savedCurrent = null;
        VariableBinding savedVar = null;
        boolean isCompound = ctx.bop.getType() != MiniJavaParser.ASSIGN;
        if (isCompound) {
            // Check if left is a simple local variable
            String varName = tryExtractSimpleName(left);
            if (varName != null) {
                savedVar = resolveVariableSilently(varName);
                if (savedVar != null) savedCurrent = savedVar.value;
            }
        }

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
            else if (classes.containsKey(innerTypeName)) elemKind = Value.Kind.CLASS;

            Value assigned = applyAssignment(elemKind, currentVal, ctx.bop.getType(), rhs);
            elements[i] = assigned;
            return assigned;
        }

        // Field assignment: obj.field = rhs or this.field = rhs
        if (left.bop != null && left.bop.getType() == MiniJavaParser.DOT && left.identifier() != null) {
            return evalFieldAssignment(left, ctx.bop.getType(), rhs);
        }

        // Check if left side is a simple identifier that is a field on this
        if (left.primary() != null && left.primary().identifier() != null) {
            String name = left.primary().identifier().getText();
            // Try local variable first
            VariableBinding variable = null;
            for (ScopeFrame scope : scopeStack) {
                variable = scope.variables.get(name);
                if (variable != null) break;
            }
            if (variable != null) {
                // It's a local variable — use normal variable assignment below
            } else if (currentObject != null && enclosingClass != null) {
                // Try as field on this
                ClassInfo declClass = findFieldDeclaringClass(enclosingClass.name, name);
                if (declClass != null) {
                    FieldInfo fi = declClass.fields.get(name);
                    String fkey = declClass.name + "." + name;
                    Value currentVal = currentObject.fields.get(fkey);
                    if (currentVal == null) { currentVal = currentObject.fields.get(name); } // fallback
                    if (currentVal == null) currentVal = defaultValueForTypeName(fi.typeName);
                    Value.Kind targetKind = resolveTypeKind(fi.typeName);
                    if (ctx.bop.getType() == MiniJavaParser.ASSIGN) {
                        rhs = applyTypeCheckedAssignment(fi.typeName, rhs);
                    }
                    Value assigned = applyAssignment(targetKind, currentVal, ctx.bop.getType(), rhs);
                    currentObject.fields.put(fkey, assigned);
                    return assigned;
                }
            }
        }

        // Simple variable assignment
        VariableBinding variable = savedVar != null ? savedVar : resolveVariable(extractAssignableName(left));
        Value currentForOp = (savedCurrent != null) ? savedCurrent : variable.value;
        if (rhs.kind() == Value.Kind.ARRAY && variable.typeName.endsWith("[]")) {
            // Validate array type compatibility before assignment
            rhs = applyTypeCheckedAssignment(variable.typeName, rhs);
        } else if (rhs.kind() == Value.Kind.NULL && variable.typeName.endsWith("[]")) {
            rhs = Value.ofNull();
        }
        Value assigned = applyAssignment(variable.declaredType, currentForOp, ctx.bop.getType(), rhs);
        variable.value = assigned;
        return assigned;
    }

    /**
     * Evaluate field assignment: obj.field op= rhs or this.field op= rhs.
     */
    private Value evalFieldAssignment(MiniJavaParser.ExpressionContext left, int assignOp, Value rhs) {
        String fieldName = left.identifier().getText();
        MiniJavaParser.ExpressionContext targetExpr = left.expression(0);

        boolean isThis = targetExpr.primary() != null && targetExpr.primary().THIS() != null;
        boolean isSuper = targetExpr.primary() != null && targetExpr.primary().SUPER() != null;

        Value objVal;
        String startClass;

        if (isThis || isSuper) {
            if (currentObject == null) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            objVal = Value.ofClassObj(currentObject);
            if (isSuper) {
                if (enclosingClass == null || enclosingClass.parentName == null) {
                    System.out.println("Process exits with 34.");
                    System.exit(34);
                }
                startClass = enclosingClass.parentName;
            } else {
                startClass = enclosingClass != null ? enclosingClass.name : currentObject.className;
            }
        } else {
            objVal = visit(targetExpr);
            startClass = objVal.getTypeName();
        }

        // Null pointer check
        if (objVal.kind() == Value.Kind.NULL) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }
        if (objVal.kind() != Value.Kind.CLASS) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        ObjectInstance obj = objVal.asClassObj();
        if (obj == null) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        // Find the declaring class to get the field type
        ClassInfo declClass = findFieldDeclaringClass(startClass, fieldName);
        if (declClass == null) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }
        FieldInfo fi = declClass.fields.get(fieldName);
        String fieldTypeName = fi.typeName;

        // Get current value (use qualified key)
        String fkey = declClass.name + "." + fieldName;
        Value currentVal = obj.fields.get(fkey);
        if (currentVal == null) { currentVal = obj.fields.get(fieldName); } // fallback
        if (currentVal == null) currentVal = Value.ofNull();

        // Determine target kind for applyAssignment
        Value.Kind targetKind = resolveTypeKind(fieldTypeName);

        // Apply type checking to rhs
        if (assignOp == MiniJavaParser.ASSIGN) {
            rhs = applyTypeCheckedAssignment(fieldTypeName, rhs);
        }

        Value assigned = applyAssignment(targetKind, currentVal, assignOp, rhs);
        obj.fields.put(declClass.name + "." + fieldName, assigned);
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
            if (rhs.kind() == Value.Kind.NULL) {
                throw new EvalException("Type error: string concatenation with null.");
            }
            return Value.ofString(currentValue.asString() + toConcatString(rhs));
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
                return rhs; // null assignable to any array, preserves type if typed
            }
            if (rhs.kind() == Value.Kind.ARRAY) {
                return rhs;
            }
            throw new EvalException("Type mismatch for array assignment.");
        }

        if (targetType == Value.Kind.CLASS) {
            // Class type assignment: allow null or same/subclass type
            if (rhs.kind() == Value.Kind.NULL) {
                return rhs; // null assignable to any class type
            }
            if (rhs.kind() == Value.Kind.CLASS) {
                return rhs; // upcast is checked elsewhere (implicitly allowed)
            }
            throw new EvalException("Type mismatch for class assignment.");
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
            if (left.kind() == Value.Kind.NULL || right.kind() == Value.Kind.NULL) {
                throw new EvalException("Type error: string concatenation with null.");
            }
            return Value.ofString(toConcatString(left) + toConcatString(right));
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
        } else if (left.kind() == Value.Kind.CLASS && right.kind() == Value.Kind.CLASS) {
            // Class object reference equality
            // Both must be in the same inheritance tree
            String leftDeclType = left.getTypeName();
            String rightDeclType = right.getTypeName();
            if (!isSameInheritanceTree(leftDeclType, rightDeclType)) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            // Reference equality (same object pointer)
            ObjectInstance leftObj = left.asClassObj();
            ObjectInstance rightObj = right.asClassObj();
            result = (leftObj == rightObj);
        } else if ((left.kind() == Value.Kind.CLASS && right.kind() == Value.Kind.NULL)
                || (left.kind() == Value.Kind.NULL && right.kind() == Value.Kind.CLASS)) {
            // classObj == null or null == classObj: check if class reference is null
            Value classVal = left.kind() == Value.Kind.CLASS ? left : right;
            result = classVal.asClassObj() == null;
        } else if (left.kind() == Value.Kind.NULL && right.kind() == Value.Kind.NULL) {
            // Both null — check if they carry typed class info in different inheritance trees
            String leftType = left.getTypeName();
            String rightType = right.getTypeName();
            if (!"null".equals(leftType) && !"null".equals(rightType)
                && classes.containsKey(leftType) && classes.containsKey(rightType)) {
                if (!isSameInheritanceTree(leftType, rightType)) {
                    System.out.println("Process exits with 34.");
                    System.exit(34);
                }
            }
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

    /** Try to extract a simple variable name from an expression, or null. */
    private String tryExtractSimpleName(MiniJavaParser.ExpressionContext expr) {
        if (expr.primary() != null && expr.primary().identifier() != null) {
            return expr.primary().identifier().getText();
        }
        return null;
    }

    /** Resolve a variable without throwing if not found. Returns null if not found. */
    private VariableBinding resolveVariableSilently(String identifier) {
        for (ScopeFrame scope : scopeStack) {
            VariableBinding variable = scope.variables.get(identifier);
            if (variable != null) return variable;
        }
        return null;
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

    /**
     * Get the display string for a value, handling class objects with to_string() dispatch.
     * For class objects: if the declared type has a suitable to_string() method,
     * invoke it via virtual dispatch (real type); otherwise return the class name.
     * For arrays: format recursively with proper class element display.
     */
    private String getDisplayString(Value val) {
        if (val.kind() == Value.Kind.NULL) return "null";
        if (val.kind() == Value.Kind.ARRAY) {
            return formatArrayDisplay((Value[]) val.getValue());
        }
        if (val.kind() != Value.Kind.CLASS) return val.displayString();

        ObjectInstance obj = val.asClassObj();
        if (obj == null) return "null";

        String declType = val.getTypeName();
        MiniJavaParser.MethodDeclarationContext toStringMethod = findToStringMethod(declType);

        if (toStringMethod == null) {
            return obj.className;
        }

        Value result = invokeToString(obj, toStringMethod);
        if (result != null && result.kind() == Value.Kind.STRING) {
            return result.asString();
        }
        return obj.className;
    }

    /** Format an array with proper recursive element display (including to_string for class elements). */
    private String formatArrayDisplay(Value[] arr) {
        if (arr == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == null) {
                sb.append("null");
            } else {
                sb.append(getDisplayString(arr[i]));
            }
            if (i < arr.length - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }

    /** Format a value for string concatenation, handling class objects with to_string() dispatch. */
    private String toConcatString(Value val) {
        if (val.kind() == Value.Kind.NULL) return "null";
        if (val.kind() == Value.Kind.ARRAY) {
            return formatArrayDisplay((Value[]) val.getValue());
        }
        if (val.kind() != Value.Kind.CLASS) return val.toConcatString();

        ObjectInstance obj = val.asClassObj();
        if (obj == null) return "null";

        String declType = val.getTypeName();
        MiniJavaParser.MethodDeclarationContext toStringMethod = findToStringMethod(declType);

        if (toStringMethod == null) {
            return obj.className;
        }

        Value result = invokeToString(obj, toStringMethod);
        if (result != null && result.kind() == Value.Kind.STRING) {
            return result.asString();
        }
        return obj.className;
    }

    /**
     * Find a suitable to_string() method in the class hierarchy starting from startClass.
     * Suitable means: no parameters, return type string.
     * Returns the method declaration from the first class that declares it (for overload resolution),
     * but the actual invocation will use virtual dispatch.
     */
    private MiniJavaParser.MethodDeclarationContext findToStringMethod(String startClass) {
        String current = startClass;
        while (current != null) {
            ClassInfo ci = classes.get(current);
            if (ci == null) break;
            for (MiniJavaParser.MethodDeclarationContext m : ci.getLocalMethods("to_string")) {
                // Check: no parameters, return type string
                boolean noParams = m.formalParameters().formalParameterList() == null
                    || m.formalParameters().formalParameterList().formalParameter().isEmpty();
                boolean returnsString = m.typeType() != null && "string".equals(m.typeType().getText());
                if (noParams && returnsString) {
                    return m; // found in declared type — will be used for virtual dispatch
                }
            }
            current = ci.parentName;
        }
        return null;
    }

    /**
     * Invoke to_string() on the given object using virtual dispatch.
     * Finds the most specific override in the real type hierarchy.
     */
    private Value invokeToString(ObjectInstance obj, MiniJavaParser.MethodDeclarationContext baseMethod) {
        // Find the most specific override in the real type hierarchy
        String realType = obj.className;
        MiniJavaParser.MethodDeclarationContext resolved = baseMethod;
        String search = realType;
        while (search != null) {
            ClassInfo ci = classes.get(search);
            if (ci == null) break;
            for (MiniJavaParser.MethodDeclarationContext m : ci.getLocalMethods("to_string")) {
                if (methodSignaturesMatch(m, baseMethod)) {
                    resolved = m;
                    search = null; // found most specific
                    break;
                }
            }
            if (search != null) {
                search = ci.parentName;
            }
        }

        // Execute the resolved method
        return executeClassMethod(resolved, Collections.emptyList(), obj);
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

    /** Resolve a type name to its Kind, handling primitives, arrays, and class types. */
    private Value.Kind resolveTypeKind(String typeStr) {
        if (typeStr.endsWith("[]")) return Value.Kind.ARRAY;
        if (classes.containsKey(typeStr)) return Value.Kind.CLASS;
        return parsePrimitiveType(typeStr);
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
            case CLASS -> Value.ofNull();
            case NULL -> Value.ofNull();
        };
    }

    private Value defaultValueForTypeName(String typeName) {
        if (typeName.endsWith("[]")) return Value.ofNullTyped(typeName);
        if (classes.containsKey(typeName)) return Value.ofNullTyped(typeName); // class types default to null
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

        // null → any array type (preserve declared type)
        if (rhsType.equals("null") && typeName.endsWith("[]")) return Value.ofNullTyped(typeName);

        // null → any class type (preserve declared type)
        if (rhsType.equals("null") && classes.containsKey(typeName)) return Value.ofNullTyped(typeName);

        // Class type assignment: subclass → superclass (upcast) only
        // Implicit downcast (superclass → subclass) is NOT allowed
        if (rhs.kind() == Value.Kind.CLASS && classes.containsKey(typeName)) {
            // Check for implicit downcast: declared type of rhs is superclass of target
            if (classes.containsKey(rhsType) && getInheritanceDistance(typeName, rhsType) > 0) {
                throw new EvalException("Type mismatch: implicit downcast not allowed.");
            }
            ObjectInstance obj = rhs.asClassObj();
            if (obj != null && getInheritanceDistance(obj.className, typeName) >= 0) {
                return Value.ofClassObjWithType(obj, typeName); // upcast: update declared type
            }
        }

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

        // ---- Class instantiation: new ClassName(args...) ----
        if (ctx.classCreatorRest() != null) {
            ClassInfo ci = classes.get(baseType);
            if (ci == null) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }

            // Evaluate arguments
            List<Value> args = new ArrayList<>();
            MiniJavaParser.ExpressionListContext exprList = ctx.classCreatorRest().expressionList();
            if (exprList != null) {
                for (MiniJavaParser.ExpressionContext argCtx : exprList.expression()) {
                    args.add(visit(argCtx));
                }
            }

            // Find matching constructor
            MiniJavaParser.ConstructorDeclarationContext ctor = findConstructor(ci, args);
            return executeConstructor(ci, ctor, args);
        }

        // ---- Array creation ----
        MiniJavaParser.ArrayCreatorRestContext rest = ctx.arrayCreatorRest();
        if (rest.arrayInitializer() != null) {
            Value init = visit(rest.arrayInitializer());
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
                if (classes.containsKey(baseType)) {
                    arr[i] = Value.ofNull(); // class type defaults to null
                } else {
                    arr[i] = defaultValueForType(getKindFromString(baseType));
                }
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

    // ========== Lab 4: Class-related visitor methods ==========

    @Override
    public Value visitClassDeclaration(MiniJavaParser.ClassDeclarationContext ctx) {
        // Handled by visitCompilationUnit; this is a no-op for direct visits.
        return null;
    }

    @Override
    public Value visitClassBody(MiniJavaParser.ClassBodyContext ctx) {
        for (MiniJavaParser.ClassBodyDeclarationContext decl : ctx.classBodyDeclaration()) {
            visitClassBodyDeclaration(decl);
        }
        return null;
    }

    @Override
    public Value visitClassBodyDeclaration(MiniJavaParser.ClassBodyDeclarationContext ctx) {
        if (ctx.fieldDeclaration() != null) {
            visitFieldDeclaration(ctx.fieldDeclaration());
        } else if (ctx.methodDeclaration() != null) {
            // Collect method into current class
            MiniJavaParser.MethodDeclarationContext md = ctx.methodDeclaration();
            String methodName = md.identifier().getText();
            currentClass.methods.computeIfAbsent(methodName, k -> new ArrayList<>()).add(md);
        } else if (ctx.constructorDeclaration() != null) {
            visitConstructorDeclaration(ctx.constructorDeclaration());
        }
        // ';' is ignored
        return null;
    }

    @Override
    public Value visitFieldDeclaration(MiniJavaParser.FieldDeclarationContext ctx) {
        String typeStr = ctx.typeType().getText();
        MiniJavaParser.VariableDeclaratorContext varCtx = ctx.variableDeclarator();
        String fieldName = varCtx.identifier().getText();

        boolean hasInit = varCtx.variableInitializer() != null;
        MiniJavaParser.VariableInitializerContext initCtx = hasInit ? varCtx.variableInitializer() : null;

        FieldInfo fi = new FieldInfo(fieldName, typeStr, hasInit, initCtx);
        if (currentClass.fields.containsKey(fieldName)) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }
        currentClass.fields.put(fieldName, fi);
        return null;
    }

    @Override
    public Value visitConstructorDeclaration(MiniJavaParser.ConstructorDeclarationContext ctx) {
        currentClass.constructors.add(ctx);
        currentClass.hasExplicitConstructor = true;
        return null;
    }

    // ========== Helper: check if a type name refers to a known class ==========

    /** Returns true if typeName is a known class (not primitive, not array). */
    private boolean isClassType(String typeName) {
        return classes.containsKey(typeName);
    }

    /** Get the ClassInfo for a given class name, or null if not a class. */
    private ClassInfo getClassInfo(String typeName) {
        return classes.get(typeName);
    }

    // ========== Task 4: Field Access ==========

    /**
     * Evaluate a DOT expression: either field access (obj.field) or method call (obj.method()).
     * Handles this.x, super.x, v.x, and this.method(), super.method(), v.method().
     */
    private Value evalDotExpression(MiniJavaParser.ExpressionContext ctx) {
        MiniJavaParser.ExpressionContext leftExpr = ctx.expression(0);

        // Check if this is a field access (identifier) or method call (methodCall) on the right
        boolean isFieldAccess = ctx.identifier() != null;
        boolean isMethodCall = ctx.methodCall() != null;

        // Detect this/super on the left side
        boolean isThis = leftExpr.primary() != null && leftExpr.primary().THIS() != null;
        boolean isSuper = leftExpr.primary() != null && leftExpr.primary().SUPER() != null;

        if (isFieldAccess) {
            String fieldName = ctx.identifier().getText();
            return evalFieldAccess(leftExpr, fieldName, isThis, isSuper);
        }

        if (isMethodCall) {
            return evalMethodCallOnObject(leftExpr, ctx.methodCall(), isThis, isSuper);
        }

        throw new EvalException("Invalid DOT expression.");
    }

    /**
     * Evaluate field access: obj.field, this.field, or super.field.
     */
    private Value evalFieldAccess(MiniJavaParser.ExpressionContext leftExpr, String fieldName,
                                   boolean isThis, boolean isSuper) {
        Value objVal;

        if (isThis || isSuper) {
            // this.x or super.x → use currentObject directly
            if (currentObject == null) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            objVal = Value.ofClassObj(currentObject);
        } else {
            objVal = visit(leftExpr);
        }

        // Null pointer check
        if (objVal.kind() == Value.Kind.NULL) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }
        if (objVal.kind() != Value.Kind.CLASS) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        ObjectInstance obj = objVal.asClassObj();
        if (obj == null) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        // Determine the starting class for field lookup
        String startClass;
        if (isSuper) {
            // super.x: start from the direct superclass of the enclosing class
            if (enclosingClass == null || enclosingClass.parentName == null) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            startClass = enclosingClass.parentName;
        } else if (isThis) {
            // this.x: start from the enclosing class (decl(this))
            startClass = enclosingClass != null ? enclosingClass.name : obj.className;
        } else {
            // v.x: start from the declared type of v
            // For array elements like arr[i].x, the declared type is the array's element type
            if (leftExpr.LBRACK() != null && leftExpr.expression().size() == 2) {
                // arr[idx] — get array's type to determine declared element type
                Value arr = visit(leftExpr.expression(0));
                String arrType = arr.getTypeName();
                if (arrType.endsWith("[]")) {
                    String elemType = arrType.substring(0, arrType.length() - 2);
                    if (classes.containsKey(elemType)) {
                        startClass = elemType;
                    } else {
                        startClass = objVal.getTypeName();
                    }
                } else {
                    startClass = objVal.getTypeName();
                }
            } else {
                startClass = objVal.getTypeName(); // declared type from variable
            }
        }

        return resolveFieldOnObject(obj, fieldName, startClass);
    }

    /**
     * Resolve a field on an object, walking the class hierarchy starting from startClass.
     * Uses qualified field keys (ClassName.fieldName) to distinguish same-named fields
     * in different classes of the inheritance chain (needed for super.x).
     */
    private Value resolveFieldOnObject(ObjectInstance obj, String fieldName, String startClass) {
        String current = startClass;
        while (current != null) {
            ClassInfo ci = classes.get(current);
            if (ci == null) break;
            FieldInfo fi = ci.fields.get(fieldName);
            if (fi != null) {
                String key = current + "." + fieldName;
                Value fieldVal = obj.fields.get(key);
                // Fallback: try unqualified key (for objects created before this fix)
                if (fieldVal == null) fieldVal = obj.fields.get(fieldName);
                return fieldVal != null ? fieldVal : Value.ofNull();
            }
            current = ci.parentName;
        }
        // Field not found
        System.out.println("Process exits with 34.");
        System.exit(34);
        return null;
    }

    /**
     * Try to resolve an unqualified method call as this.method(args...).
     * Returns the result value if a matching method is found, or null if not.
     * Uses two-phase dispatch: Phase 1 on decl(this), Phase 2 on real(this).
     */
    private Value tryResolveThisMethod(String methodName, List<Value> args) {
        if (currentObject == null || enclosingClass == null) return null;

        // Phase 1: Overload resolution on declaring class and its ancestors
        MiniJavaParser.MethodDeclarationContext bestMethod = null;
        int bestCost = Integer.MAX_VALUE;
        boolean ambiguous = false;

        String search = enclosingClass.name;
        while (search != null) {
            ClassInfo ci = classes.get(search);
            if (ci == null) break;
            for (MiniJavaParser.MethodDeclarationContext m : ci.getLocalMethods(methodName)) {
                List<MiniJavaParser.FormalParameterContext> params =
                    (m.formalParameters().formalParameterList() != null)
                        ? m.formalParameters().formalParameterList().formalParameter()
                        : Collections.emptyList();
                if (params.size() != args.size()) continue;
                int cost = 0;
                boolean compatible = true;
                for (int i = 0; i < args.size(); i++) {
                    String paramType = params.get(i).typeType().getText();
                    int c = getArgConversionCost(args.get(i), paramType);
                    if (c == -1) { compatible = false; break; }
                    cost += c;
                }
                if (compatible) {
                    if (cost < bestCost) { bestCost = cost; bestMethod = m; ambiguous = false; }
                    else if (cost == bestCost) {
                        if (!methodSignaturesMatch(bestMethod, m)) ambiguous = true;
                        // Same signature: prefer more specific (subclass) method
                    }
                }
            }
            search = ci.parentName;
        }

        if (bestMethod == null || ambiguous) return null;

        // Phase 2: Override resolution on real(this) type
        String realType = currentObject.className;
        MiniJavaParser.MethodDeclarationContext resolved = bestMethod;
        String overrideSearch = realType;
        while (overrideSearch != null) {
            ClassInfo ci = classes.get(overrideSearch);
            if (ci == null) break;
            for (MiniJavaParser.MethodDeclarationContext m : ci.getLocalMethods(methodName)) {
                if (methodSignaturesMatch(m, bestMethod)) {
                    resolved = m;
                    overrideSearch = null;
                    break;
                }
            }
            if (overrideSearch != null) overrideSearch = ci.parentName;
        }

        Value ret = executeClassMethod(resolved, args, currentObject);
        // For void methods, executeClassMethod returns null.
        // Return a sentinel to indicate the method was found and executed (vs. not found).
        return (ret != null) ? ret : Value.ofInt(Integer.MIN_VALUE); // sentinel for void
    }

    /**
     * Find which class declares a field, walking up from startClass.
     * Returns the ClassInfo where the field is declared, or null.
     */
    private ClassInfo findFieldDeclaringClass(String startClass, String fieldName) {
        String current = startClass;
        while (current != null) {
            ClassInfo ci = classes.get(current);
            if (ci == null) break;
            if (ci.fields.containsKey(fieldName)) return ci;
            current = ci.parentName;
        }
        return null;
    }

    /**
     * Evaluate a method call on an object: obj.method(args), this.method(args), super.method(args).
     * Uses two-phase dispatch (overload resolution + override resolution).
     * Full implementation in Task 5; basic implementation here.
     */
    private Value evalMethodCallOnObject(MiniJavaParser.ExpressionContext leftExpr,
                                          MiniJavaParser.MethodCallContext callCtx,
                                          boolean isThis, boolean isSuper) {
        String methodName = callCtx.identifier().getText();

        // Evaluate arguments
        List<Value> args = new ArrayList<>();
        if (callCtx.arguments().expressionList() != null) {
            for (MiniJavaParser.ExpressionContext argCtx : callCtx.arguments().expressionList().expression()) {
                args.add(visit(argCtx));
            }
        }

        // Determine receiver object and declared type
        Value receiverVal;
        String startClass; // for overload resolution (declared type)

        if (isThis || isSuper) {
            if (currentObject == null) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            receiverVal = Value.ofClassObj(currentObject);
            if (isSuper) {
                if (enclosingClass == null || enclosingClass.parentName == null) {
                    System.out.println("Process exits with 34.");
                    System.exit(34);
                }
                startClass = enclosingClass.parentName;
            } else {
                startClass = enclosingClass != null ? enclosingClass.name : currentObject.className;
            }
        } else {
            // For array elements like arr[i].method(), resolve the array first to get element type
            boolean isArrayAccess = leftExpr.LBRACK() != null && leftExpr.expression().size() == 2;
            String arrayElemType = null;
            if (isArrayAccess) {
                Value arr = visit(leftExpr.expression(0));
                String arrType = arr.getTypeName();
                if (arrType.endsWith("[]")) {
                    String elemType = arrType.substring(0, arrType.length() - 2);
                    if (classes.containsKey(elemType)) arrayElemType = elemType;
                }
            }
            receiverVal = visit(isArrayAccess ? leftExpr : leftExpr);
            // Actually for array access, visit(leftExpr) gives the element, which is the receiver
            if (isArrayAccess) {
                receiverVal = visit(leftExpr); // this visits arr[idx] → returns the element
            }
            if (receiverVal.kind() == Value.Kind.NULL) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            if (receiverVal.kind() != Value.Kind.CLASS) {
                System.out.println("Process exits with 34.");
                System.exit(34);
            }
            if (arrayElemType != null) {
                startClass = arrayElemType;
            } else {
                startClass = receiverVal.getTypeName(); // declared type
            }
        }

        ObjectInstance receiver = receiverVal.asClassObj();
        if (receiver == null) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        // Phase 1: Overload resolution on declared type hierarchy
        MiniJavaParser.MethodDeclarationContext bestMethod = null;
        int bestCost = Integer.MAX_VALUE;
        boolean ambiguous = false;

        String searchClass = startClass;
        while (searchClass != null) {
            ClassInfo ci = classes.get(searchClass);
            if (ci == null) break;
            for (MiniJavaParser.MethodDeclarationContext m : ci.getLocalMethods(methodName)) {
                List<MiniJavaParser.FormalParameterContext> params =
                    (m.formalParameters().formalParameterList() != null)
                        ? m.formalParameters().formalParameterList().formalParameter()
                        : Collections.emptyList();
                if (params.size() != args.size()) continue;

                int currentCost = 0;
                boolean compatible = true;
                for (int i = 0; i < args.size(); i++) {
                    String paramType = params.get(i).typeType().getText();
                    int cost = getArgConversionCost(args.get(i), paramType);
                    if (cost == -1) { compatible = false; break; }
                    currentCost += cost;
                }
                if (compatible) {
                    if (currentCost < bestCost) {
                        bestCost = currentCost;
                        bestMethod = m;
                        ambiguous = false;
                    } else if (currentCost == bestCost) {
                        // Same cost: only ambiguous if different signatures
                        if (!methodSignaturesMatch(bestMethod, m)) {
                            ambiguous = true;
                        }
                        // Same signature → not ambiguous; keep the more specific one (already in bestMethod)
                    }
                }
            }
            searchClass = ci.parentName;
        }

        if (bestMethod == null || ambiguous) {
            // If not found in class hierarchy and not this/super, try top-level methods
            if (!isThis && !isSuper) {
                // Fall back to top-level method call
                return visitMethodCall(callCtx);
            }
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        // Phase 2: Override resolution on real type (or startClass for super)
        String overrideSearch = isSuper ? startClass : receiver.className;
        MiniJavaParser.MethodDeclarationContext resolvedMethod = bestMethod;
        while (overrideSearch != null) {
            ClassInfo ci = classes.get(overrideSearch);
            if (ci == null) break;
            for (MiniJavaParser.MethodDeclarationContext m : ci.getLocalMethods(methodName)) {
                if (methodSignaturesMatch(m, bestMethod)) {
                    resolvedMethod = m;
                    // Found the most specific override; break out of both loops
                    overrideSearch = null;
                    break;
                }
            }
            if (overrideSearch != null) {
                overrideSearch = ci.parentName;
            }
        }

        return executeClassMethod(resolvedMethod, args, receiver);
    }

    /** Check if two method declarations have the same signature (name + parameter types). */
    private boolean methodSignaturesMatch(MiniJavaParser.MethodDeclarationContext a,
                                           MiniJavaParser.MethodDeclarationContext b) {
        List<MiniJavaParser.FormalParameterContext> paramsA =
            (a.formalParameters().formalParameterList() != null)
                ? a.formalParameters().formalParameterList().formalParameter()
                : Collections.emptyList();
        List<MiniJavaParser.FormalParameterContext> paramsB =
            (b.formalParameters().formalParameterList() != null)
                ? b.formalParameters().formalParameterList().formalParameter()
                : Collections.emptyList();
        if (paramsA.size() != paramsB.size()) return false;
        for (int i = 0; i < paramsA.size(); i++) {
            if (!paramsA.get(i).typeType().getText().equals(paramsB.get(i).typeType().getText()))
                return false;
        }
        return true;
    }

    /**
     * Execute a class method with the given receiver object.
     * Sets up currentObject and enclosingClass for the duration of the call.
     */
    private Value executeClassMethod(MiniJavaParser.MethodDeclarationContext m,
                                      List<Value> args, ObjectInstance receiver) {
        ObjectInstance savedObj = this.currentObject;
        ClassInfo savedEnclosing = this.enclosingClass;
        this.currentObject = receiver;

        // Determine which class owns this method
        String methodClassName = getMethodOwnerClassName(m);
        this.enclosingClass = classes.get(methodClassName);

        Deque<ScopeFrame> savedStack = new ArrayDeque<>(scopeStack);
        scopeStack.clear();
        enterScope();

        try {
            // Bind parameters
            List<MiniJavaParser.FormalParameterContext> params =
                (m.formalParameters().formalParameterList() != null)
                    ? m.formalParameters().formalParameterList().formalParameter()
                    : Collections.emptyList();
            for (int i = 0; i < params.size(); i++) {
                String pName = params.get(i).identifier().getText();
                String pTypeStr = params.get(i).typeType().getText();
                Value argVal = args.get(i);
                argVal = applyArgConversion(argVal, pTypeStr);
                Value.Kind declaredKind = resolveTypeKind(pTypeStr);
                currentScope().variables.put(pName,
                    new VariableBinding(declaredKind, pTypeStr, argVal));
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

            boolean isVoid = m.VOID() != null;
            if (isVoid) {
                if (ret != null) {
                    System.out.println("Process exits with 34.");
                    System.exit(34);
                }
                return null;
            } else {
                String retType = m.typeType().getText();
                // null return is valid for class types and array types
                if (ret == null) {
                    if (retType.endsWith("[]") || classes.containsKey(retType)) {
                        ret = Value.ofNullTyped(retType);
                    } else {
                        System.out.println("Process exits with 34.");
                        System.exit(34);
                    }
                }
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
                    } else if (classes.containsKey(retType) && ret.getTypeName().equals("null")) {
                        ret = Value.ofNullTyped(retType);
                    } else if (classes.containsKey(retType) && ret.kind() == Value.Kind.CLASS) {
                        // Subclass → superclass return: re-wrap with correct declared type
                        ret = Value.ofClassObjWithType(ret.asClassObj(), retType);
                    }
                }
                if (ret.isDecimalLiteral()) {
                    ret = Value.ofInt(ret.asIntegral());
                }
                return ret;
            }
        } finally {
            scopeStack.clear();
            scopeStack.addAll(savedStack);
            this.currentObject = savedObj;
            this.enclosingClass = savedEnclosing;
        }
    }

    /**
     * Find which class owns a given method declaration.
     * We search all classes to find the one containing this method.
     */
    private String getMethodOwnerClassName(MiniJavaParser.MethodDeclarationContext m) {
        for (ClassInfo ci : classes.values()) {
            for (List<MiniJavaParser.MethodDeclarationContext> overloads : ci.methods.values()) {
                if (overloads.contains(m)) return ci.name;
            }
        }
        // If not found by reference, try matching by method name + signature
        if (m != null && m.identifier() != null) {
            String mName = m.identifier().getText();
            for (ClassInfo ci : classes.values()) {
                if (ci.methods.containsKey(mName)) {
                    for (MiniJavaParser.MethodDeclarationContext candidate : ci.methods.get(mName)) {
                        if (methodSignaturesMatch(candidate, m)) return ci.name;
                    }
                }
            }
        }
        return null;
    }

    // ========== Task 3: Object Creation & Constructors ==========

    /** The object currently being constructed/accessed (used during field init and constructor body). */
    private ObjectInstance currentObject = null;

    /** The class that owns the currently executing method/constructor (for super resolution). */
    private ClassInfo enclosingClass = null;

    /**
     * Find the best matching constructor for the given arguments using overload resolution.
     * If no explicit constructor matches, returns the implicit default constructor (or null if none).
     */
    private MiniJavaParser.ConstructorDeclarationContext findConstructor(ClassInfo ci, List<Value> args) {
        MiniJavaParser.ConstructorDeclarationContext best = null;
        int bestCost = Integer.MAX_VALUE;
        boolean ambiguous = false;

        for (MiniJavaParser.ConstructorDeclarationContext c : ci.constructors) {
            List<MiniJavaParser.FormalParameterContext> params =
                (c.formalParameters().formalParameterList() != null)
                    ? c.formalParameters().formalParameterList().formalParameter()
                    : Collections.emptyList();

            if (params.size() != args.size()) continue;

            int currentCost = 0;
            boolean compatible = true;
            for (int i = 0; i < args.size(); i++) {
                String paramType = params.get(i).typeType().getText();
                int cost = getArgConversionCost(args.get(i), paramType);
                if (cost == -1) {
                    compatible = false;
                    break;
                }
                currentCost += cost;
            }

            if (compatible) {
                if (currentCost < bestCost) {
                    bestCost = currentCost;
                    best = c;
                    ambiguous = false;
                } else if (currentCost == bestCost) {
                    ambiguous = true;
                }
            }
        }

        if (ambiguous) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        }

        if (best != null) return best;

        // No matching explicit constructor → use implicit default if args is empty
        // BUT: if the class has explicit constructors, no implicit default is generated
        if (args.isEmpty() && !ci.hasExplicitConstructor) {
            return null; // signal to use implicit default constructor
        }

        System.out.println("Process exits with 34.");
        System.exit(34);
        return null;
    }

    /**
     * Execute a constructor using the 4-step process:
     * Step 1: explicit this(...) / super(...)
     * Step 2: implicit super() if no explicit delegation
     * Step 3: field initializers (if no this() delegation)
     * Step 4: remaining constructor body
     */
    private Value executeConstructor(ClassInfo ci, MiniJavaParser.ConstructorDeclarationContext ctor,
                                      List<Value> args) {
        // Allocate and zero-initialize the object
        ObjectInstance obj = allocateAndZeroFields(ci);
        ObjectInstance savedObj = this.currentObject;
        this.currentObject = obj;

        // Save and restore scope state (constructor body runs in its own scope)
        Deque<ScopeFrame> savedStack = new ArrayDeque<>(scopeStack);
        scopeStack.clear();
        enterScope();

        try {
            // Bind parameters
            if (ctor != null) {
                List<MiniJavaParser.FormalParameterContext> params =
                    (ctor.formalParameters().formalParameterList() != null)
                        ? ctor.formalParameters().formalParameterList().formalParameter()
                        : Collections.emptyList();
                for (int i = 0; i < params.size(); i++) {
                    String pName = params.get(i).identifier().getText();
                    String pTypeStr = params.get(i).typeType().getText();
                    Value argVal = args.get(i);
                    argVal = applyArgConversion(argVal, pTypeStr);
                    Value.Kind declaredKind = resolveTypeKind(pTypeStr);
                    currentScope().variables.put(pName,
                        new VariableBinding(declaredKind, pTypeStr, argVal));
                }
            }

            // Execute the 4-step constructor process
            executeConstructorBody(ci, ctor);

        } catch (EvalException ex) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        } finally {
            scopeStack.clear();
            scopeStack.addAll(savedStack);
            this.currentObject = savedObj;
        }

        return Value.ofClassObj(obj);
    }

    /**
     * Execute the constructor body following the 4-step process:
     * 1. Detect and handle this(...)/super(...) as first statement
     * 2. If no explicit delegation, call implicit super() on parent
     * 3. If no this() delegation, execute field initializers
     * 4. Execute remaining constructor body statements
     */
    private void executeConstructorBody(ClassInfo ci, MiniJavaParser.ConstructorDeclarationContext ctor) {
        ClassInfo savedEnclosing = this.enclosingClass;
        this.enclosingClass = ci;

        MiniJavaParser.BlockContext block = (ctor != null) ? ctor.block() : null;
        List<MiniJavaParser.BlockStatementContext> statements = (block != null)
            ? block.blockStatement() : Collections.emptyList();

        int startIdx = 0;
        boolean didExplicitDelegation = false;
        boolean didThisDelegation = false;

        // ---- Step 1: Check for explicit this(...) / super(...) as first statement ----
        if (!statements.isEmpty()) {
            MiniJavaParser.BlockStatementContext first = statements.get(0);
            String delegationType = detectConstructorDelegation(first);
            if (delegationType != null) {
                didExplicitDelegation = true;
                startIdx = 1;

                MiniJavaParser.ExpressionContext expr = first.statement().expression();
                MiniJavaParser.MethodCallContext callCtx = expr.methodCall();
                List<Value> delegationArgs = new ArrayList<>();
                if (callCtx.arguments().expressionList() != null) {
                    for (MiniJavaParser.ExpressionContext argCtx
                         : callCtx.arguments().expressionList().expression()) {
                        delegationArgs.add(visit(argCtx));
                    }
                }

                if ("this".equals(delegationType)) {
                    didThisDelegation = true;
                    // Find another constructor in the same class
                    MiniJavaParser.ConstructorDeclarationContext targetCtor =
                        findConstructor(ci, delegationArgs);
                    // Execute the target constructor on the same object
                    executeConstructorDelegated(ci, targetCtor, delegationArgs, currentObject);
                } else { // "super"
                    ClassInfo superCi = classes.get(ci.parentName);
                    if (superCi == null) {
                        System.out.println("Process exits with 34.");
                        System.exit(34);
                    }
                    MiniJavaParser.ConstructorDeclarationContext superCtor =
                        findConstructor(superCi, delegationArgs);
                    executeSuperConstructor(superCi, superCtor, delegationArgs);
                }
            }
        }

        // ---- Step 2: Implicit super() if no explicit delegation ----
        if (!didExplicitDelegation && ci.parentName != null) {
            ClassInfo superCi = classes.get(ci.parentName);
            // Find the default constructor of the superclass (may be explicit or implicit)
            MiniJavaParser.ConstructorDeclarationContext superCtor =
                findConstructor(superCi, Collections.emptyList());
            executeSuperConstructor(superCi, superCtor, Collections.emptyList());
        }

        // ---- Step 3: Field initializers (only if no this() delegation) ----
        if (!didThisDelegation) {
            initFields(ci);
        }

        // ---- Step 4: Execute remaining constructor body ----
        if (ctor != null && block != null) {
            for (int i = startIdx; i < statements.size(); i++) {
                visit(statements.get(i));
            }
        }

        this.enclosingClass = savedEnclosing;
    }

    /**
     * Execute a constructor that was delegated to via this(...).
     * The object is already allocated; we skip allocation and field init for the
     * delegated constructor's own class (since the outer constructor handles it).
     */
    private void executeConstructorDelegated(ClassInfo ci, MiniJavaParser.ConstructorDeclarationContext ctor,
                                              List<Value> args, ObjectInstance obj) {
        ObjectInstance savedObj = this.currentObject;
        this.currentObject = obj;

        Deque<ScopeFrame> savedStack = new ArrayDeque<>(scopeStack);
        scopeStack.clear();
        enterScope();

        try {
            // Bind parameters
            List<MiniJavaParser.FormalParameterContext> params =
                (ctor.formalParameters().formalParameterList() != null)
                    ? ctor.formalParameters().formalParameterList().formalParameter()
                    : Collections.emptyList();
            for (int i = 0; i < params.size(); i++) {
                String pName = params.get(i).identifier().getText();
                String pTypeStr = params.get(i).typeType().getText();
                Value argVal = args.get(i);
                argVal = applyArgConversion(argVal, pTypeStr);
                Value.Kind declaredKind = resolveTypeKind(pTypeStr);
                currentScope().variables.put(pName,
                    new VariableBinding(declaredKind, pTypeStr, argVal));
            }

            // Recurse: executeConstructorBody with the delegated constructor
            executeConstructorBody(ci, ctor);

        } catch (EvalException ex) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        } finally {
            scopeStack.clear();
            scopeStack.addAll(savedStack);
            this.currentObject = savedObj;
        }
    }

    /**
     * Execute a superclass constructor. The currentObject must already be allocated.
     * This handles the super() call (explicit or implicit).
     */
    private void executeSuperConstructor(ClassInfo superCi,
                                          MiniJavaParser.ConstructorDeclarationContext superCtor,
                                          List<Value> args) {
        ObjectInstance savedObj = this.currentObject;
        // currentObject stays the same (same object, superclass init)

        Deque<ScopeFrame> savedStack = new ArrayDeque<>(scopeStack);
        scopeStack.clear();
        enterScope();

        try {
            // Bind parameters
            if (superCtor != null) {
                List<MiniJavaParser.FormalParameterContext> params =
                    (superCtor.formalParameters().formalParameterList() != null)
                        ? superCtor.formalParameters().formalParameterList().formalParameter()
                        : Collections.emptyList();
                for (int i = 0; i < params.size(); i++) {
                    String pName = params.get(i).identifier().getText();
                    String pTypeStr = params.get(i).typeType().getText();
                    Value argVal = args.get(i);
                    argVal = applyArgConversion(argVal, pTypeStr);
                    Value.Kind declaredKind = resolveTypeKind(pTypeStr);
                    currentScope().variables.put(pName,
                        new VariableBinding(declaredKind, pTypeStr, argVal));
                }
            }

            // Execute the superclass constructor body
            executeConstructorBody(superCi, superCtor);

        } catch (EvalException ex) {
            System.out.println("Process exits with 34.");
            System.exit(34);
        } finally {
            scopeStack.clear();
            scopeStack.addAll(savedStack);
            this.currentObject = savedObj;
        }
    }

    /**
     * Detect if the first statement is a constructor delegation: this(...) or super(...).
     * Returns "this", "super", or null.
     */
    private String detectConstructorDelegation(MiniJavaParser.BlockStatementContext stmt) {
        if (stmt.statement() == null) return null;
        MiniJavaParser.StatementContext s = stmt.statement();
        if (s.expression() == null) return null;
        MiniJavaParser.ExpressionContext expr = s.expression();
        if (expr.methodCall() == null) return null;
        MiniJavaParser.MethodCallContext call = expr.methodCall();
        if (call.THIS() != null) return "this";
        if (call.SUPER() != null) return "super";
        return null;
    }

    /**
     * Allocate an object and zero-initialize ALL fields across the entire
     * inheritance chain (from the topmost superclass down to the given class).
     */
    private ObjectInstance allocateAndZeroFields(ClassInfo ci) {
        // Build inheritance chain from top to bottom
        List<ClassInfo> chain = new ArrayList<>();
        ClassInfo current = ci;
        while (current != null) {
            chain.add(current);
            current = classes.get(current.parentName);
        }
        // Reverse to get top-down order
        Collections.reverse(chain);

        ObjectInstance obj = new ObjectInstance(ci.name);

        // Zero all fields in top-down order
        for (ClassInfo c : chain) {
            for (FieldInfo fi : c.fields.values()) {
                Value defaultVal;
                if (fi.typeName.endsWith("[]") || classes.containsKey(fi.typeName)) {
                    defaultVal = Value.ofNull();
                } else {
                    defaultVal = defaultValueForTypeName(fi.typeName);
                }
                obj.fields.put(c.name + "." + fi.name, defaultVal);
            }
        }

        return obj;
    }

    /**
     * Execute field initializers for the given class on the current object.
     * Fields are initialized in declaration order.
     */
    private void initFields(ClassInfo ci) {
        for (FieldInfo fi : ci.fields.values()) {
            if (fi.hasInitializer) {
                // Evaluate the initializer expression
                Value initVal = visit(fi.initializerCtx);

                // Apply type checking
                if (fi.typeName.endsWith("[]") || classes.containsKey(fi.typeName)) {
                    // Array or class type
                    if (initVal.kind() == Value.Kind.NULL) {
                        // OK, null is valid
                    } else if (fi.typeName.endsWith("[]") && initVal.kind() == Value.Kind.ARRAY) {
                        initVal = applyTypeCheckedAssignment(fi.typeName, initVal);
                    } else if (classes.containsKey(fi.typeName) && initVal.kind() == Value.Kind.CLASS) {
                        // Check class type compatibility
                        initVal = applyTypeCheckedAssignment(fi.typeName, initVal);
                    } else {
                        System.out.println("Process exits with 34.");
                        System.exit(34);
                    }
                } else {
                    // Primitive type
                    initVal = applyTypeCheckedAssignment(fi.typeName, initVal);
                }

                currentObject.fields.put(ci.name + "." + fi.name, initVal);
            }
        }
    }

    /**
     * Conversion cost for method/constructor argument matching.
     * For class types, cost = inheritance distance (0 = same class, 1 = direct superclass, etc.)
     */
    private int getArgConversionCost(Value arg, String formalParamType) {
        String argType = arg.getTypeName();

        // Exact match
        if (argType.equals(formalParamType)) return 0;

        // char → int
        if (argType.equals("char") && formalParamType.equals("int")) return 1;

        // null → any array type or class type
        if (argType.equals("null") && (formalParamType.endsWith("[]") || classes.containsKey(formalParamType)))
            return 1;

        // Class upcast: subclass → superclass (flat cost = 1 per spec)
        if (arg.kind() == Value.Kind.CLASS && classes.containsKey(formalParamType)) {
            ObjectInstance obj = arg.asClassObj();
            if (obj != null) {
                int dist = getInheritanceDistance(obj.className, formalParamType);
                if (dist >= 0) return 1; // all upcasts cost 1, not depth
            }
        }

        // null → class type (handled above via argType.equals("null"))
        return -1;
    }

    /** Apply argument conversion (e.g., char→int widening) for constructor/method parameters. */
    private Value applyArgConversion(Value arg, String paramType) {
        if (arg.getTypeName().equals("char") && paramType.equals("int")) {
            return Value.ofInt(arg.asIntegral());
        }
        if (arg.isDecimalLiteral()) {
            return Value.ofInt(arg.asIntegral());
        }
        return arg;
    }

    /**
     * Calculate inheritance distance from subclass to superclass.
     * Returns 0 if same class, 1 if direct subclass, etc.
     * Returns -1 if subclass is not actually a subclass of superclass.
     */
    private int getInheritanceDistance(String subClassName, String superClassName) {
        if (subClassName.equals(superClassName)) return 0;
        int distance = 0;
        String current = subClassName;
        while (current != null) {
            ClassInfo ci = classes.get(current);
            if (ci == null) break;
            current = ci.parentName;
            distance++;
            if (superClassName.equals(current)) return distance;
        }
        return -1;
    }

    /**
     * Check if two class types belong to the same inheritance tree
     * (i.e., one is reachable from the other via extends chain).
     */
    private boolean isSameInheritanceTree(String classA, String classB) {
        // Check if A is ancestor of B or B is ancestor of A
        if (getInheritanceDistance(classA, classB) >= 0) return true;
        if (getInheritanceDistance(classB, classA) >= 0) return true;
        return false;
    }
}
