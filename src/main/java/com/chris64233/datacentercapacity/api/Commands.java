package com.chris64233.datacentercapacity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;
import java.util.Map;

/** 写操作命令对象。 */
public final class Commands {

    private Commands() {
    }

    public record CreateZone(@NotBlank String code, @PositiveOrZero long coolingCapacityW) {
    }

    public record UpdateZone(@PositiveOrZero Long coolingCapacityW) {
    }

    public record CreateRack(@NotBlank String code,
                             @Positive int totalUnits,
                             @PositiveOrZero long feedACapacityW,
                             @PositiveOrZero long feedBCapacityW,
                             @NotNull Long zoneId) {
    }

    public record UpdateRack(@Positive Integer totalUnits,
                             @PositiveOrZero Long feedACapacityW,
                             @PositiveOrZero Long feedBCapacityW) {
    }

    public record SubmitRequest(@NotBlank String requestId,
                                String deviceName,
                                @Positive int uHeight,
                                @PositiveOrZero long primaryPowerW,
                                @PositiveOrZero long backupPowerW,
                                @PositiveOrZero long heatLoadW,
                                @NotEmpty List<Long> allowedRackIds) {
    }

    /**
     * 批准命令：expectedVersion 为申请当前版本（乐观锁）；
     * expectedRackVersions 可选，声明批准所基于的机柜容量配置版本，配置已变化则整体失败。
     */
    public record Approve(long expectedVersion, Map<Long, Long> expectedRackVersions) {
    }

    public record Migrate(@NotNull Long targetRackId, Long expectedVersion) {
    }
}
