package com.chris64233.datacentercapacity.api;

import java.time.Instant;
import java.util.List;

/** 查询视图对象。 */
public final class Views {

    private Views() {
    }

    public record ZoneView(long id, String code, long coolingCapacityW,
                           long usedCoolingW, long availableCoolingW, long version) {
    }

    public record RackView(long id, String code, long zoneId, String zoneCode,
                           int totalUnits, int usedUnits, int freeUnits,
                           long feedACapacityW, long feedAUsedW,
                           long feedBCapacityW, long feedBUsedW,
                           List<int[]> occupiedRanges, long version) {
    }

    public record ReservationView(long id, String requestId, long rackId, String rackCode,
                                  int uStart, int uEnd,
                                  String primaryFeed, String backupFeed,
                                  long primaryPowerW, long backupPowerW, long heatLoadW,
                                  String status, Instant createdAt,
                                  Instant releasedAt, String releaseReason) {
    }

    public record RequestView(long id, String requestId, String deviceName,
                              int uHeight, long primaryPowerW, long backupPowerW, long heatLoadW,
                              List<Long> allowedRackIds, String status, long version,
                              Instant decommissionedAt, ReservationView reservation) {
    }

    public record EventView(long id, Instant occurredAt, String type,
                            String requestId, Long rackId, Long zoneId, String detail) {
    }

    public record DecommissionView(String requestId, String status,
                                   Instant decommissionedAt, Long releasedReservationId) {
    }

    /** 提交结果：created=false 表示命中幂等键，返回的是既有申请。 */
    public record SubmitResult(RequestView view, boolean created) {
    }
}
