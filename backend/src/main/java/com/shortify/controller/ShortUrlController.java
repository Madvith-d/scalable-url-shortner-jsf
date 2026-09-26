package com.shortify.controller;

import java.net.URI;

import com.shortify.dto.CreateShortUrlRequest;
import com.shortify.dto.ShortUrlPage;
import com.shortify.dto.ShortUrlResponse;
import com.shortify.dto.UpdateShortUrlRequest;
import com.shortify.service.ShortUrlService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/urls")
public class ShortUrlController {

    private final ShortUrlService service;

    public ShortUrlController(ShortUrlService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request) {
        ShortUrlResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/api/urls/" + response.id())).body(response);
    }

    @GetMapping
    public ShortUrlPage list(@RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @PatchMapping("/{id}")
    public ShortUrlResponse update(@PathVariable Long id, @Valid @RequestBody UpdateShortUrlRequest request) {
        return service.update(id, request.active());
    }

    @GetMapping("/{id}")
    public ShortUrlResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
