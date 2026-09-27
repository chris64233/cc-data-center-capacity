package com.chris64233.datacentercapacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一条资源预留：某申请在某机柜上占用的连续机位段、双路电力与制冷容量。
 * 主备电源永远分配到不同供电回路，记录主路所在回路，备路即另一路。
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    /** 释放原因：整体迁移到新位置。 */
    public static final String REASON_MIGRATED = "MIGRATED";
    /** 释放原因：设备下架。 */
    public static final String REASON_DECOMMISSIONED = "DECOMMISSIONED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private DeviceRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rack_id", nullable = false)
    private Rack rack;

    @Column(nullable = false)
    private int uStart;

    @Column(nullable = false)
    private int uEnd;

    /** 主路电源所在回路；备路电源在另一回路上。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Feed primaryFeed;

    @Column(nullable = false)
    private long primaryPowerW;

    @Column(nullable = false)
    private long backupPowerW;

    @Column(nullable = false)
    private long heatLoadW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status = ReservationStatus.ACTIVE;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant releasedAt;

    private String releaseReason;

    protected Reservation() {
    }

    public Reservation(DeviceRequest request, Rack rack, int uStart, int uEnd, Feed primaryFeed,
                       long primaryPowerW, long backupPowerW, long heatLoadW) {
        this.request = request;
        this.rack = rack;
        this.uStart = uStart;
        this.uEnd = uEnd;
        this.primaryFeed = primaryFeed;
        this.primaryPowerW = primaryPowerW;
        this.backupPowerW = backupPowerW;
        this.heatLoadW = heatLoadW;
    }

    /** 释放该预留占用的全部资源。 */
    public void release(String reason) {
        this.status = ReservationStatus.RELEASED;
        this.releasedAt = Instant.now();
        this.releaseReason = reason;
    }

    public int getUHeight() {
        return uEnd - uStart + 1;
    }

    /** 指定回路当前被该预留占用的电力。 */
    public long powerOn(Feed feed) {
        return primaryFeed == feed ? primaryPowerW : backupPowerW;
    }

    public Long getId() {
        return id;
    }

    public DeviceRequest getRequest() {
        return request;
    }

    public Rack getRack() {
        return rack;
    }

    public int getUStart() {
        return uStart;
    }

    public int getUEnd() {
        return uEnd;
    }

    public Feed getPrimaryFeed() {
        return primaryFeed;
    }

    public long getPrimaryPowerW() {
        return primaryPowerW;
    }

    public long getBackupPowerW() {
        return backupPowerW;
    }

    public long getHeatLoadW() {
        return heatLoadW;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public String getReleaseReason() {
        return releaseReason;
    }
}
