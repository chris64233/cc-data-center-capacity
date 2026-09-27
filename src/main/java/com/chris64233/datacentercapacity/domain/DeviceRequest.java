package com.chris64233.datacentercapacity.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Version;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备上架申请。requestNo 为幂等键。
 * 内容（机位高度、主备电力、散热量、允许机柜范围）任何修改都会使 version 递增，
 * 基于旧版本的批准将被拒绝。
 */
@Entity
public class DeviceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String requestNo;

    /** 机位高度（U） */
    private int uHeight;

    /** 主路电力需求（瓦） */
    private long primaryPowerW;

    /** 备路电力需求（瓦） */
    private long backupPowerW;

    /** 散热量（瓦），占用所属制冷区域容量 */
    private long heatLoadW;

    /** 允许上架的机柜范围 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "device_request_racks", joinColumns = @JoinColumn(name = "request_id"))
    @Column(name = "rack_id")
    private List<Long> allowedRackIds = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status = RequestStatus.PENDING;

    @Version
    private long version;

    protected DeviceRequest() {
    }

    public DeviceRequest(String requestNo, int uHeight, long primaryPowerW, long backupPowerW,
                         long heatLoadW, List<Long> allowedRackIds) {
        this.requestNo = requestNo;
        this.uHeight = uHeight;
        this.primaryPowerW = primaryPowerW;
        this.backupPowerW = backupPowerW;
        this.heatLoadW = heatLoadW;
        this.allowedRackIds = new ArrayList<>(allowedRackIds);
    }

    public boolean contentEquals(int uHeight, long primaryPowerW, long backupPowerW,
                                 long heatLoadW, List<Long> allowedRackIds) {
        return this.uHeight == uHeight
                && this.primaryPowerW == primaryPowerW
                && this.backupPowerW == backupPowerW
                && this.heatLoadW == heatLoadW
                // 持久化集合未实现 equals，拷贝后按元素比较
                && new ArrayList<>(this.allowedRackIds).equals(allowedRackIds);
    }

    public void update(int uHeight, long primaryPowerW, long backupPowerW,
                       long heatLoadW, List<Long> allowedRackIds) {
        this.uHeight = uHeight;
        this.primaryPowerW = primaryPowerW;
        this.backupPowerW = backupPowerW;
        this.heatLoadW = heatLoadW;
        this.allowedRackIds = new ArrayList<>(allowedRackIds);
    }

    public Long getId() {
        return id;
    }

    public String getRequestNo() {
        return requestNo;
    }

    public int getUHeight() {
        return uHeight;
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

    public List<Long> getAllowedRackIds() {
        return allowedRackIds;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public long getVersion() {
        return version;
    }
}
