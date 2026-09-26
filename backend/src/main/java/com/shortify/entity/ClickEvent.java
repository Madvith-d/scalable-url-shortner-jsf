package com.shortify.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "click_events")
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_url_id", nullable = false)
    private Long shortUrlId;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;

    @Column(nullable = false, length = 253)
    private String referrer;

    @Column(nullable = false, length = 16)
    private String device;

    @Column(nullable = false, length = 64)
    private String geography;

    protected ClickEvent() { }

    public ClickEvent(Long shortUrlId, Instant accessedAt, String referrer, String device, String geography) {
        this.shortUrlId = shortUrlId;
        this.accessedAt = accessedAt;
        this.referrer = referrer;
        this.device = device;
        this.geography = geography;
    }
}
