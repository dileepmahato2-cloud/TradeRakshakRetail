package com.traderakshak.exception;
import lombok.Getter;
@Getter
public class TradeRakshakException extends RuntimeException {
    private final String code;
    public TradeRakshakException(String code, String message) {
        super(message);
        this.code = code;
    }
}
