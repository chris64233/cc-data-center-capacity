package com.chris64233.datacentercapacity.domain;

/** 设备申请生命周期：待批准 → 已批准（已预留）→ 已下架。 */
public enum RequestStatus {
    PENDING,
    APPROVED,
    DECOMMISSIONED
}
