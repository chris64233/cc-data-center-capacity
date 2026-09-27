package com.chris64233.datacentercapacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;

/** 机柜，记录机位总数、双路（A/B）电力容量与已用量，以及所属制冷区域。 */
@Entity
public class Rack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /** 机柜总机位（U 数），机位编号 1..totalU */
    private int totalU;

    private long feedACapacityW;

    private long feedBCapacityW;

    private long usedFeedAW;

    private long usedFeedBW;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private CoolingZone zone;

    @Version
    private long version;

    protected Rack() {
    }

    public Rack(String name, int totalU, long feedACapacityW, long feedBCapacityW, CoolingZone zone) {
        this.name = name;
        this.totalU = totalU;
        this.feedACapacityW = feedACapacityW;
        this.feedBCapacityW = feedBCapacityW;
        this.zone = zone;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getTotalU() {
        return totalU;
    }

    public void setTotalU(int totalU) {
        this.totalU = totalU;
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

    public long getUsedFeedAW() {
        return usedFeedAW;
    }

    public long getUsedFeedBW() {
        return usedFeedBW;
    }

    public void addUsedFeedAW(long delta) {
        this.usedFeedAW += delta;
    }

    public void addUsedFeedBW(long delta) {
        this.usedFeedBW += delta;
    }

    public CoolingZone getZone() {
        return zone;
    }

    public long getVersion() {
        return version;
    }
}
