package com.campuspulse;

class ApiException extends RuntimeException {
    final int statusCode;
    final String code;

    private ApiException(int statusCode, String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    static ApiException gone(String code, String message) {
        return new ApiException(410, code, message);
    }
}
