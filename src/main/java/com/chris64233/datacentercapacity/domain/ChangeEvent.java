package com.chris64233.datacentercapacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/** 变更事件，用于审计与查询。 */
@Entity
public class ChangeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;

    private String requestNo;

    private Long reservationId;

    private Long rackId;

    private Long zoneId;

    @Column(length = 2000)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt;

    protected ChangeEvent() {
    }

    public ChangeEvent(EventType type, String requestNo, Long reservationId, Long rackId, Long zoneId, String detail) {
        this.type = type;
        this.requestNo = requestNo;
        this.reservationId = reservationId;
        this.rackId = rackId;
        this.zoneId = zoneId;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public EventType getType() {
        return type;
    }

    public String getRequestNo() {
        return requestNo;
    }

    public Long getReservationId() {
        return reservationId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
