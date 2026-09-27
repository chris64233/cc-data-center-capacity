package com.chris64233.datacentercapacity.domain;

/** 变更事件类型。 */
public enum EventType {
    ZONE_CONFIG_UPDATED,
    RACK_CONFIG_UPDATED,
    REQUEST_SUBMITTED,
    REQUEST_UPDATED,
    REQUEST_APPROVED,
    RESERVATION_MIGRATED,
    DEVICE_DECOMMISSIONED
}
