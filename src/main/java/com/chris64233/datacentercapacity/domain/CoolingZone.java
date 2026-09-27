package com.chris64233.datacentercapacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

/** 制冷区域，记录区域总制冷量与已预留制冷量（瓦）。 */
@Entity
public class CoolingZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private long totalCoolingW;

    private long usedCoolingW;

    @Version
    private long version;

    protected CoolingZone() {
    }

    public CoolingZone(String name, long totalCoolingW) {
        this.name = name;
        this.totalCoolingW = totalCoolingW;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getTotalCoolingW() {
        return totalCoolingW;
    }

    public void setTotalCoolingW(long totalCoolingW) {
        this.totalCoolingW = totalCoolingW;
    }

    public long getUsedCoolingW() {
        return usedCoolingW;
    }

    public void addUsedCoolingW(long delta) {
        this.usedCoolingW += delta;
    }

    public long getVersion() {
        return version;
    }
}
