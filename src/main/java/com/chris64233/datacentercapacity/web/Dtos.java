package com.chris64233.datacentercapacity.web;

import com.chris64233.datacentercapacity.domain.Feed;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/** API 请求/响应模型。 */
public final class Dtos {

    private Dtos() {
    }

    // ---------- 制冷区域 ----------
    public record CreateZoneRequest(@NotBlank String name, @Min(0) long totalCoolingW) {
    }

    public record UpdateZoneRequest(@Min(0) long expectedVersion, @Min(0) long totalCoolingW) {
    }

    public record ZoneView(long id, String name, long totalCoolingW, long usedCoolingW,
                           long freeCoolingW, long version) {
    }

    // ---------- 机柜 ----------
    public record CreateRackRequest(@NotBlank String name, @Min(1) int totalU,
                                    @Min(0) long feedACapacityW, @Min(0) long feedBCapacityW,
                                    @NotNull Long zoneId) {
    }

    public record UpdateRackRequest(@Min(0) long expectedVersion,
                                    @Min(1) Integer totalU,
                                    @Min(0) Long feedACapacityW,
                                    @Min(0) Long feedBCapacityW) {
    }

    public record RackView(long id, String name, int totalU, int usedU, int freeU,
                           long feedACapacityW, long usedFeedAW, long freeFeedAW,
                           long feedBCapacityW, long usedFeedBW, long freeFeedBW,
                           long zoneId, String zoneName, long version) {
    }

    // ---------- 设备申请 ----------
    public record SubmitRequestDto(@NotBlank String requestNo,
                                   @Min(1) int uHeight,
                                   @Min(0) long primaryPowerW,
                                   @Min(0) long backupPowerW,
                                   @Min(0) long heatLoadW,
                                   @NotEmpty List<Long> allowedRackIds) {
    }

    public record UpdateRequestDto(@Min(0) long expectedVersion,
                                   @Min(1) int uHeight,
                                   @Min(0) long primaryPowerW,
                                   @Min(0) long backupPowerW,
                                   @Min(0) long heatLoadW,
                                   @NotEmpty List<Long> allowedRackIds) {
    }

    public record RequestView(long id, String requestNo, int uHeight,
                              long primaryPowerW, long backupPowerW, long heatLoadW,
                              List<Long> allowedRackIds, String status, long version) {
    }

    /**
     * 批准申请。expectedRequestVersion 必填：申请内容变化后基于旧版本的批准失败。
     * rackId/expectedRackVersion 可选：指定机柜时，机柜容量配置变化后基于旧版本的批准失败。
     */
    public record ApproveDto(@Min(0) long expectedRequestVersion,
                             Long rackId,
                             Long expectedRackVersion,
                             Feed primaryFeed) {
    }

    // ---------- 预留 ----------
    public record MigrateDto(@NotNull Long targetRackId,
                             @Min(1) Integer uStart,
                             Feed primaryFeed,
                             @Min(0) Long expectedVersion) {
    }

    public record ReservationView(long id, String requestNo, long rackId, String rackName,
                                  long zoneId, String zoneName,
                                  int uStart, int uHeight, Feed primaryFeed,
                                  long feedAPowerW, long feedBPowerW, long coolingW,
                                  String status, long version,
                                  Instant createdAt, Instant deliveredAt, Instant releasedAt) {
    }

    // ---------- 事件 ----------
    public record EventView(long id, String type, String requestNo, Long reservationId,
                            Long rackId, Long zoneId, String detail, Instant createdAt) {
    }

    public record ErrorBody(String code, String message) {
    }
}
