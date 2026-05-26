package com.urlshortener.url_shortener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class UrlService {

    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String CACHE_PREFIX = "url:";
    private static final long CACHE_TTL = 24;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String encode(long id) {
        StringBuilder sb = new StringBuilder();
        while (id > 0) {
            sb.append(BASE62.charAt((int)(id % 62)));
            id /= 62;
        }
        return sb.reverse().toString();
    }

    public Url shortenUrl(String originalUrl) {
        Url url = new Url();
        url.setOriginalUrl(originalUrl);
        url.setShortCode("x");
        Url saved = urlRepository.save(url);

        String shortCode = encode(saved.getId());
        saved.setShortCode(shortCode);
        Url result = urlRepository.save(saved);

        // Cache it in Redis
        redisTemplate.opsForValue().set(
            CACHE_PREFIX + shortCode,
            originalUrl,
            CACHE_TTL,
            TimeUnit.HOURS
        );

        return result;
    }

    public Optional<Url> getOriginalUrl(String shortCode) {
        // Check Redis cache first
        String cached = redisTemplate.opsForValue().get(CACHE_PREFIX + shortCode);
        if (cached != null) {
            // Still update click count in DB async
            urlRepository.findByShortCode(shortCode).ifPresent(u -> {
                u.setClickCount(u.getClickCount() + 1);
                urlRepository.save(u);
            });
            // Return from cache
            Url cachedUrl = new Url();
            cachedUrl.setOriginalUrl(cached);
            cachedUrl.setShortCode(shortCode);
            return Optional.of(cachedUrl);
        }

        // Cache miss → hit DB
        Optional<Url> url = urlRepository.findByShortCode(shortCode);
        url.ifPresent(u -> {
            u.setClickCount(u.getClickCount() + 1);
            urlRepository.save(u);
            // Store in cache for next time
            redisTemplate.opsForValue().set(
                CACHE_PREFIX + shortCode,
                u.getOriginalUrl(),
                CACHE_TTL,
                TimeUnit.HOURS
            );
        });
        return url;
    }
}