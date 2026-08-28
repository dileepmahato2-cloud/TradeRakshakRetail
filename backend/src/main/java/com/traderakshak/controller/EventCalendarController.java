package com.traderakshak.controller;

import com.traderakshak.dto.response.ApiResponse;
import com.traderakshak.service.EventCalendarService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/events")
public class EventCalendarController {
    @PersistenceContext
    private EntityManager em;
    private final EventCalendarService service;

    public EventCalendarController(EventCalendarService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> events(@RequestParam(defaultValue = "") String country,
                                                                   @RequestParam(defaultValue = "") String eventType,
                                                                   @RequestParam(defaultValue = "") String stock,
                                                                   @RequestParam(defaultValue = "") String sector,
                                                                   @RequestParam(defaultValue = "") String from,
                                                                   @RequestParam(defaultValue = "") String to,
                                                                   @RequestParam(defaultValue = "") String impact,
                                                                   @RequestParam(defaultValue = "") String search) {
        return ResponseEntity.ok(ApiResponse.ok(service.dashboard(country, eventType, stock, sector, from, to, impact, search)));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(@RequestBody Map<String, Object> req) {
        Long id = service.saveEvent(null, req);
        return ResponseEntity.ok(ApiResponse.ok("Event saved.", Map.of("id", id, "dashboard", service.dashboard("", "", "", "", "", "", "", ""))));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable Long id,
                                                                   @RequestBody Map<String, Object> req) {
        service.saveEvent(id, req);
        return ResponseEntity.ok(ApiResponse.ok("Event updated.", Map.of("id", id, "dashboard", service.dashboard("", "", "", "", "", "", "", ""))));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> delete(@PathVariable Long id) {
        em.createNativeQuery("UPDATE event_calendar SET status='deleted',updated_at=? WHERE id=?")
            .setParameter(1, service.now()).setParameter(2, id).executeUpdate();
        return ResponseEntity.ok(ApiResponse.ok("Event deleted.", service.dashboard("", "", "", "", "", "", "", "")));
    }

    @PostMapping("/fetch-latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> fetchLatest() {
        Map<String, Object> result = service.fetchLatestWithRetry("Manual Fetch");
        return ResponseEntity.ok(ApiResponse.ok("Latest event fetch completed.", result));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> today() {
        return ResponseEntity.ok(ApiResponse.ok(service.todayEvents()));
    }

    @GetMapping("/high-impact")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> highImpact() {
        return ResponseEntity.ok(ApiResponse.ok(service.highImpactEvents()));
    }

    @GetMapping("/sources")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> sources() {
        return ResponseEntity.ok(ApiResponse.ok(service.sources()));
    }

    @PostMapping("/sources")
    @Transactional
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> saveSource(@RequestBody Map<String, Object> req) {
        service.saveSource(req);
        return ResponseEntity.ok(ApiResponse.ok("Event source saved.", service.sources()));
    }

    @PutMapping("/sources/{id}")
    @Transactional
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> updateSource(@PathVariable Long id,
                                                                               @RequestBody Map<String, Object> req) {
        req.put("id", id);
        service.saveSource(req);
        return ResponseEntity.ok(ApiResponse.ok("Event source updated.", service.sources()));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportEvents() {
        StringBuilder sb = new StringBuilder("title,type,country,market,stock,sector,date,time,impact,source,status,description\n");
        for (Map<String, Object> e : service.events("", "", "", "", "", "", "", "", 10000)) {
            sb.append(csv(e.get("eventTitle"))).append(',').append(csv(e.get("eventType"))).append(',').append(csv(e.get("country"))).append(',')
                .append(csv(e.get("market"))).append(',').append(csv(e.get("stockSymbol"))).append(',').append(csv(e.get("sector"))).append(',')
                .append(csv(e.get("eventDate"))).append(',').append(csv(e.get("eventTime"))).append(',').append(csv(e.get("impactLevel"))).append(',')
                .append(csv(e.get("source"))).append(',').append(csv(e.get("status"))).append(',').append(csv(e.get("description"))).append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDisposition(ContentDisposition.attachment().filename("events_calendar.csv").build());
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    private String csv(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
