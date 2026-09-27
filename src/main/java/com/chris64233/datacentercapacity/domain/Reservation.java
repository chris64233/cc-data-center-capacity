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
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 设备预留。一次性占用机位区间、双路电力和制冷容量。
 * 主路落在 primaryFeed 上，备路落在另一路上，二者永不同路。
 */
@Entity
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private DeviceRequest request;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "rack_id")
    private Rack rack;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private CoolingZone zone;

    /** 起始机位（含），占用 [uStart, uStart+uHeight-1] */
    private int uStart;

    private int uHeight;

    /** 主路所在回路；备路在 primaryFeed.other() */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Feed primaryFeed;

    private long feedAPowerW;

    private long feedBPowerW;

    private long coolingW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status = ReservationStatus.RESERVED;

    private Instant createdAt;

    private Instant deliveredAt;

    private Instant releasedAt;

    @Version
    private long version;

    protected Reservation() {
    }

    public Reservation(DeviceRequest request, Rack rack, CoolingZone zone, int uStart, int uHeight,
                       Feed primaryFeed, long feedAPowerW, long feedBPowerW, long coolingW) {
        this.request = request;
        this.rack = rack;
        this.zone = zone;
        this.uStart = uStart;
        this.uHeight = uHeight;
        this.primaryFeed = primaryFeed;
        this.feedAPowerW = feedAPowerW;
        this.feedBPowerW = feedBPowerW;
        this.coolingW = coolingW;
        // 截断到毫秒，保证与数据库时间精度一致（幂等比较依赖相等性）
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    public boolean isActive() {
        return status != ReservationStatus.RELEASED;
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

    public void setRack(Rack rack) {
        this.rack = rack;
    }

    public CoolingZone getZone() {
        return zone;
    }

    public void setZone(CoolingZone zone) {
        this.zone = zone;
    }

    public int getUStart() {
        return uStart;
    }

    public void setUStart(int uStart) {
        this.uStart = uStart;
    }

    public int getUHeight() {
        return uHeight;
    }

    public Feed getPrimaryFeed() {
        return primaryFeed;
    }

    public void setPrimaryFeed(Feed primaryFeed) {
        this.primaryFeed = primaryFeed;
    }

    public long getFeedAPowerW() {
        return feedAPowerW;
    }

    public void setFeedAPowerW(long feedAPowerW) {
        this.feedAPowerW = feedAPowerW;
    }

    public long getFeedBPowerW() {
        return feedBPowerW;
    }

    public void setFeedBPowerW(long feedBPowerW) {
        this.feedBPowerW = feedBPowerW;
    }

    public long getCoolingW() {
        return coolingW;
    }

    public void setCoolingW(long coolingW) {
        this.coolingW = coolingW;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    public long getVersion() {
        return version;
    }
}
