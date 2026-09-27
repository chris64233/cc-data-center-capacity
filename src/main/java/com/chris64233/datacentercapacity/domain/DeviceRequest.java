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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备上架申请。requestId 是业务幂等键；version 用于乐观并发控制，
 * 申请内容每次变更都会使版本号递增，基于旧版本的批准将被拒绝。
 */
@Entity
@Table(name = "device_request")
public class DeviceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务申请号，全局唯一，作为幂等键。 */
    @Column(nullable = false, unique = true)
    private String requestId;

    private String deviceName;

    /** 机位高度（U）。 */
    @Column(nullable = false)
    private int uHeight;

    /** 主路电力需求（W）。 */
    @Column(nullable = false)
    private long primaryPowerW;

    /** 备路电力需求（W）。 */
    @Column(nullable = false)
    private long backupPowerW;

    /** 散热量（W），占用所属制冷区域容量。 */
    @Column(nullable = false)
    private long heatLoadW;

    /** 允许上架的机柜范围（机柜 ID 列表，按优先级排序）。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "device_request_racks", joinColumns = @JoinColumn(name = "request_id"))
    @Column(name = "rack_id")
    @OrderColumn(name = "idx")
    private List<Long> allowedRackIds = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant decommissionedAt;

    @Version
    private long version;

    protected DeviceRequest() {
    }

    public DeviceRequest(String requestId, String deviceName, int uHeight,
                         long primaryPowerW, long backupPowerW, long heatLoadW,
                         List<Long> allowedRackIds) {
        this.requestId = requestId;
        this.deviceName = deviceName;
        this.uHeight = uHeight;
        this.primaryPowerW = primaryPowerW;
        this.backupPowerW = backupPowerW;
        this.heatLoadW = heatLoadW;
        this.allowedRackIds = new ArrayList<>(allowedRackIds);
    }

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public int getUHeight() {
        return uHeight;
    }

    public void setUHeight(int uHeight) {
        this.uHeight = uHeight;
    }

    public long getPrimaryPowerW() {
        return primaryPowerW;
    }

    public void setPrimaryPowerW(long primaryPowerW) {
        this.primaryPowerW = primaryPowerW;
    }

    public long getBackupPowerW() {
        return backupPowerW;
    }

    public void setBackupPowerW(long backupPowerW) {
        this.backupPowerW = backupPowerW;
    }

    public long getHeatLoadW() {
        return heatLoadW;
    }

    public void setHeatLoadW(long heatLoadW) {
        this.heatLoadW = heatLoadW;
    }

    public List<Long> getAllowedRackIds() {
        return allowedRackIds;
    }

    public void setAllowedRackIds(List<Long> allowedRackIds) {
        this.allowedRackIds = new ArrayList<>(allowedRackIds);
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecommissionedAt() {
        return decommissionedAt;
    }

    public void setDecommissionedAt(Instant decommissionedAt) {
        this.decommissionedAt = decommissionedAt;
    }

    public long getVersion() {
        return version;
    }
}
