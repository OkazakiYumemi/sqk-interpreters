package cn.edu.nju.cs;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.File;

public class Main {
    private static final String ERROR_MESSAGE = "Process exits with 34.";

    public static void run(File mjFile) throws Exception {
        var input = CharStreams.fromFileName(mjFile.getAbsolutePath());
        MiniJavaLexer lexer = new MiniJavaLexer(input);
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        MiniJavaParser parser = new MiniJavaParser(tokenStream);
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);

        ParseTree pt = parser.compilationUnit();
        Value result = new EvalVisitor().visit(pt);
        if (result == null) {
            throw new EvalException("Evaluation result is null.");
        }
        System.out.print(result.displayString());
    }


    public static void main(String[] args) throws Exception  {
        if(args.length!= 1) {
            failAndExit();
        }

        try {
            File mjFile = new File(args[0]);
            run(mjFile);
        } catch (Exception ex) {
            failAndExit();
        }
    }

    private static void failAndExit() {
        System.out.println(ERROR_MESSAGE);
        System.exit(34);
    }

    private static class ThrowingErrorListener extends BaseErrorListener {
        private static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();

        @Override
        public void syntaxError(
            Recognizer<?, ?> recognizer,
            Object offendingSymbol,
            int line,
            int charPositionInLine,
            String msg,
            RecognitionException e
        ) {
            throw new EvalException("Syntax error.");
        }
    }
}