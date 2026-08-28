package com.traderakshak.controller;

import com.traderakshak.util.UserMessage;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.security.JwtService;
import com.traderakshak.service.LivePriceBroadcaster;
import com.traderakshak.service.DhanLivePriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/live-prices")
@RequiredArgsConstructor
@Slf4j
public class LivePriceController {

    private final DhanLivePriceService livePriceService;
    private final LivePriceBroadcaster broadcaster;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        return ResponseEntity.ok(ApiResponse.ok(livePriceService.status()));
    }

    @GetMapping("/quotes")
    public ResponseEntity<ApiResponse<Collection<Map<String, Object>>>> quotes() {
        return ResponseEntity.ok(ApiResponse.ok(livePriceService.latestQuotes()));
    }

    @PostMapping("/restart")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restart() {
        livePriceService.restart();
        return ResponseEntity.ok(ApiResponse.ok("Live price service reconnecting.", livePriceService.status()));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam("token") String token) {
        if (!isTokenValid(token)) {
            // EventSource clients send Accept: text/event-stream, so the JSON error body
            // produced by GlobalExceptionHandler cannot be negotiated and turns a stale
            // token into a 500. Answer with a bare 401 so the client re-authenticates.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(broadcaster.register());
    }

    private boolean isTokenValid(String token) {
        try {
            String username = jwtService.extractUsername(token);
            var userDetails = userDetailsService.loadUserByUsername(username);
            return jwtService.isTokenValid(token, userDetails);
        } catch (Exception e) {
            log.debug("Live price stream token rejected: {}", UserMessage.of(e));
            return false;
        }
    }
}
