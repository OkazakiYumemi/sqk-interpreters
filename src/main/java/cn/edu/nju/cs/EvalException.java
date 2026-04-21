package cn.edu.nju.cs;

public class EvalException extends RuntimeException {
    public EvalException(String message) {
        super(message);
    }

    public EvalException(String message, Throwable cause) {
        super(message, cause);
    }
}
