package com.chris64233.datacentercapacity.domain;

public enum EventType {
    REQUEST_CREATED,
    REQUEST_UPDATED,
    /** 批准申请并完成一次性预留 */
    RESERVED,
    /** 交付前整体迁移预留 */
    MIGRATED,
    DELIVERED,
    /** 下架并一次性释放全部资源 */
    RELEASED,
    RACK_CONFIG_CHANGED,
    ZONE_CONFIG_CHANGED
}
