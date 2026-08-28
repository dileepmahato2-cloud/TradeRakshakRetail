package com.traderakshak.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/** Standard API envelope — R4: disclaimer on every response, R15: errorCode exact */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private String  message;
    private T       data;
    private String  errorCode;

    @Builder.Default
    private String disclaimer =
        "\u26a0 DISCLAIMER: TradeRakshak\u2122 is a Technology Tools Platform. It is NOT a SEBI-registered Investment Advisor or Research Analyst. All content is for educational purposes only. Past performance does not guarantee future results. Consult a SEBI-empanelled advisor before trading.";

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder().success(true).data(data).build();
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder().success(true).message(message).data(data).build();
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return ApiResponse.<T>builder().success(false).errorCode(code).message(message).build();
    }
}
