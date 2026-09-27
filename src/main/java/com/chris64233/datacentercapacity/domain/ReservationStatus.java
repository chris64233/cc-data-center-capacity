package com.chris64233.datacentercapacity.domain;

public enum ReservationStatus {
    /** 已预留，尚未交付（此状态下允许整体迁移） */
    RESERVED,
    /** 已交付（交付后不可再迁移） */
    DELIVERED,
    /** 已下架释放 */
    RELEASED
}
