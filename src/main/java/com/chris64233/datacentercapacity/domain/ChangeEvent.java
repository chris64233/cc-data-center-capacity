package com.chris64233.datacentercapacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 变更事件：容量配置、申请与预留的关键变更审计记录。 */
@Entity
@Table(name = "change_event")
public class ChangeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant occurredAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType type;

    private String requestId;

    private Long rackId;

    private Long zoneId;

    @Column(length = 2000)
    private String detail;

    protected ChangeEvent() {
    }

    public ChangeEvent(EventType type, String requestId, Long rackId, Long zoneId, String detail) {
        this.type = type;
        this.requestId = requestId;
        this.rackId = rackId;
        this.zoneId = zoneId;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public EventType getType() {
        return type;
    }

    public String getRequestId() {
        return requestId;
    }

    public Long getRackId() {
        return rackId;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public String getDetail() {
        return detail;
    }
}
