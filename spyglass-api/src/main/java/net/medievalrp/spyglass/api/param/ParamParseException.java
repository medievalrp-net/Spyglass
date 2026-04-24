package net.medievalrp.spyglass.api.param;

public final class ParamParseException extends Exception {

    public ParamParseException(String message) {
        super(message);
    }

    public ParamParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
