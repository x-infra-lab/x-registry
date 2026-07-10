package com.x.registry.api.exception;

public class XRegistryException extends RuntimeException {

    private int errorCode;

    public XRegistryException(String message) {
        super(message);
    }

    public XRegistryException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public XRegistryException(String message, Throwable cause) {
        super(message, cause);
    }

    public int getErrorCode() {
        return errorCode;
    }

    public static XRegistryException notFound(String resource) {
        return new XRegistryException(404, resource + " not found");
    }

    public static XRegistryException invalidParam(String param) {
        return new XRegistryException(400, "Invalid parameter: " + param);
    }

    public static XRegistryException serverError(String message) {
        return new XRegistryException(500, message);
    }
}
