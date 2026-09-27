package com.chris64233.datacentercapacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 机柜：总机位数、A/B 双路电力容量、所属制冷区域。版本号用于乐观并发控制。 */
@Entity
@Table(name = "rack")
public class Rack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    /** 机柜总机位数（U），机位编号 1..totalUnits。 */
    @Column(nullable = false)
    private int totalUnits;

    @Column(nullable = false)
    private long feedACapacityW;

    @Column(nullable = false)
    private long feedBCapacityW;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zone_id", nullable = false)
    private CoolingZone zone;

    @Version
    private long version;

    protected Rack() {
    }

    public Rack(String code, int totalUnits, long feedACapacityW, long feedBCapacityW, CoolingZone zone) {
        this.code = code;
        this.totalUnits = totalUnits;
        this.feedACapacityW = feedACapacityW;
        this.feedBCapacityW = feedBCapacityW;
        this.zone = zone;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public int getTotalUnits() {
        return totalUnits;
    }

    public void setTotalUnits(int totalUnits) {
        this.totalUnits = totalUnits;
    }

    public long getFeedACapacityW() {
        return feedACapacityW;
    }

    public void setFeedACapacityW(long feedACapacityW) {
        this.feedACapacityW = feedACapacityW;
    }

    public long getFeedBCapacityW() {
        return feedBCapacityW;
    }

    public void setFeedBCapacityW(long feedBCapacityW) {
        this.feedBCapacityW = feedBCapacityW;
    }

    public CoolingZone getZone() {
        return zone;
    }

    public long getVersion() {
        return version;
    }
}
