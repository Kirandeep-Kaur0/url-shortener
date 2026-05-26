package com.urlshortener.url_shortener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@RestController
public class UrlController {

    @Autowired
    private UrlService urlService;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final int MAX_REQUESTS = 10;
    private static final String RATE_PREFIX = "rate:";

    // Rate limiter — max 10 requests per minute per IP
    private boolean isRateLimited(String ip) {
        String key = RATE_PREFIX + ip;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, 1, TimeUnit.MINUTES);
        }
        return count > MAX_REQUESTS;
    }

    @PostMapping("/shorten")
    public ResponseEntity<?> shorten(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Forwarded-For", defaultValue = "unknown") String ip) {

        if (isRateLimited(ip)) {
            return ResponseEntity.status(429).body(Map.of(
                "error", "Too many requests. Max 10 per minute."
            ));
        }

        String originalUrl = body.get("url");
        if (originalUrl == null || originalUrl.isBlank()) {
            throw new IllegalArgumentException("URL cannot be empty");
        }
        if (!originalUrl.startsWith("http://") && !originalUrl.startsWith("https://")) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }

        Url url = urlService.shortenUrl(originalUrl);
        return ResponseEntity.ok(Map.of(
            "shortCode", url.getShortCode(),
            "shortUrl", "http://localhost:8080/" + url.getShortCode(),
            "originalUrl", url.getOriginalUrl()
        ));
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<?> redirect(@PathVariable String shortCode) {
        Optional<Url> url = urlService.getOriginalUrl(shortCode);
        if (url.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                "error", "Short URL not found"
            ));
        }
        return ResponseEntity.status(302)
            .location(URI.create(url.get().getOriginalUrl()))
            .build();
    }

    @GetMapping("/stats/{shortCode}")
    public ResponseEntity<?> stats(@PathVariable String shortCode) {
        Optional<Url> url = urlRepository.findByShortCode(shortCode);
        if (url.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of(
                "error", "Short URL not found"
            ));
        }
        return ResponseEntity.ok(Map.of(
            "shortCode", shortCode,
            "originalUrl", url.get().getOriginalUrl(),
            "clicks", url.get().getClickCount(),
            "createdAt", url.get().getCreatedAt().toString()
        ));
    }

    @GetMapping("/api/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("URL Shortener is running!");
    }
}