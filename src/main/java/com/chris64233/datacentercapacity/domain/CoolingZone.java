package com.chris64233.datacentercapacity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 制冷区域：记录区域总制冷容量（按散热量瓦特计），版本号用于乐观并发控制。 */
@Entity
@Table(name = "cooling_zone")
public class CoolingZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private long coolingCapacityW;

    @Version
    private long version;

    protected CoolingZone() {
    }

    public CoolingZone(String code, long coolingCapacityW) {
        this.code = code;
        this.coolingCapacityW = coolingCapacityW;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public long getCoolingCapacityW() {
        return coolingCapacityW;
    }

    public void setCoolingCapacityW(long coolingCapacityW) {
        this.coolingCapacityW = coolingCapacityW;
    }

    public long getVersion() {
        return version;
    }
}
