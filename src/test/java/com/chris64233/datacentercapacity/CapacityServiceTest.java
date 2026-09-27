package com.chris64233.datacentercapacity;

import com.chris64233.datacentercapacity.api.Commands;
import com.chris64233.datacentercapacity.api.Views;
import com.chris64233.datacentercapacity.repo.ChangeEventRepository;
import com.chris64233.datacentercapacity.repo.CoolingZoneRepository;
import com.chris64233.datacentercapacity.repo.DeviceRequestRepository;
import com.chris64233.datacentercapacity.repo.RackRepository;
import com.chris64233.datacentercapacity.repo.ReservationRepository;
import com.chris64233.datacentercapacity.service.BusinessException;
import com.chris64233.datacentercapacity.service.CapacityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CapacityServiceTest {

    @Autowired
    CapacityService service;
    @Autowired
    ReservationRepository reservationRepo;
    @Autowired
    DeviceRequestRepository requestRepo;
    @Autowired
    RackRepository rackRepo;
    @Autowired
    CoolingZoneRepository zoneRepo;
    @Autowired
    ChangeEventRepository eventRepo;

    @BeforeEach
    void clean() {
        reservationRepo.deleteAll();
        eventRepo.deleteAll();
        requestRepo.deleteAll();
        rackRepo.deleteAll();
        zoneRepo.deleteAll();
    }

    private Views.ZoneView zone(String code, long capacityW) {
        return service.createZone(new Commands.CreateZone(code, capacityW));
    }

    private Views.RackView rack(String code, int units, long feedA, long feedB, long zoneId) {
        return service.createRack(new Commands.CreateRack(code, units, feedA, feedB, zoneId));
    }

    private Views.RequestView submit(String requestId, int uHeight, long primary, long backup,
                                     long heat, List<Long> rackIds) {
        return service.submit(new Commands.SubmitRequest(requestId, "srv-" + requestId,
                uHeight, primary, backup, heat, rackIds)).view();
    }

    // ------------------------------------------------------------------
    // 原子预留
    // ------------------------------------------------------------------

    @Test
    void approveReservesPositionPowerAndCoolingAtomically() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 5_000, 5_000, zone.id());
        submit("REQ-1", 4, 1_000, 800, 600, List.of(rack.id()));

        var view = service.approve("REQ-1", new Commands.Approve(0, null));

        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.reservation().rackId()).isEqualTo(rack.id());
        assertThat(view.reservation().uStart()).isEqualTo(1);
        assertThat(view.reservation().uEnd()).isEqualTo(4);

        var rackView = service.getRack(rack.id());
        assertThat(rackView.usedUnits()).isEqualTo(4);
        assertThat(rackView.feedAUsedW() + rackView.feedBUsedW()).isEqualTo(1_800);

        var zoneView = service.getZone(zone.id());
        assertThat(zoneView.usedCoolingW()).isEqualTo(600);
    }

    @Test
    void approveRejectsAsWholeWhenCoolingInsufficient() {
        var zone = zone("Z1", 500); // 制冷容量小于申请散热量
        var rack = rack("R1", 42, 5_000, 5_000, zone.id());
        submit("REQ-1", 4, 1_000, 800, 600, List.of(rack.id()));

        assertThatThrownBy(() -> service.approve("REQ-1", new Commands.Approve(0, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));

        // 整体拒绝：机位与电力均未产生任何预留
        var rackView = service.getRack(rack.id());
        assertThat(rackView.usedUnits()).isZero();
        assertThat(rackView.feedAUsedW()).isZero();
        assertThat(rackView.feedBUsedW()).isZero();
        assertThat(reservationRepo.findAll()).isEmpty();
        assertThat(service.getRequest("REQ-1").status()).isEqualTo("PENDING");
    }

    @Test
    void approveRejectsWhenNoRackCanFit() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 4, 5_000, 5_000, zone.id());
        submit("REQ-1", 10, 100, 100, 100, List.of(rack.id())); // 机位高度超过机柜

        assertThatThrownBy(() -> service.approve("REQ-1", new Commands.Approve(0, null)))
                .isInstanceOf(BusinessException.class);
        assertThat(service.getRack(rack.id()).usedUnits()).isZero();
    }

    // ------------------------------------------------------------------
    // 主备电源不得同路
    // ------------------------------------------------------------------

    @Test
    void primaryAndBackupAreNeverOnTheSameFeed() {
        var zone = zone("Z1", 10_000);
        // A 路 1500W、B 路 800W：主路 1200W 只能接 A，备路 500W 只能接 B
        var rack = rack("R1", 42, 1_500, 800, zone.id());
        submit("REQ-1", 2, 1_200, 500, 100, List.of(rack.id()));

        var view = service.approve("REQ-1", new Commands.Approve(0, null));

        assertThat(view.reservation().primaryFeed()).isEqualTo("A");
        assertThat(view.reservation().backupFeed()).isEqualTo("B");
        assertThat(view.reservation().primaryFeed()).isNotEqualTo(view.reservation().backupFeed());
    }

    @Test
    void approveFailsWhenSingleFeedCannotCarryEitherSide() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 1_000, 1_000, zone.id());
        // 主路 1200W 超过任一路容量：无论怎么分配都不可能
        submit("REQ-1", 2, 1_200, 100, 100, List.of(rack.id()));

        assertThatThrownBy(() -> service.approve("REQ-1", new Commands.Approve(0, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void secondRequestUsesTheOtherFeedWhenFirstFeedIsTight() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 1_000, 1_000, zone.id());
        submit("REQ-1", 2, 900, 100, 100, List.of(rack.id()));
        submit("REQ-2", 2, 900, 100, 100, List.of(rack.id()));

        var first = service.approve("REQ-1", new Commands.Approve(0, null));
        var second = service.approve("REQ-2", new Commands.Approve(0, null));

        // 第一台主路占 A 路 900W 后，第二台主路只能接 B 路
        assertThat(first.reservation().primaryFeed()).isEqualTo("A");
        assertThat(second.reservation().primaryFeed()).isEqualTo("B");
        assertThat(service.getRack(rack.id()).feedAUsedW()).isEqualTo(1_000);
        assertThat(service.getRack(rack.id()).feedBUsedW()).isEqualTo(1_000);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void submitIsIdempotentByRequestId() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 5_000, 5_000, zone.id());
        var cmd = new Commands.SubmitRequest("REQ-1", "srv", 2, 100, 100, 100, List.of(rack.id()));

        var first = service.submit(cmd);
        var second = service.submit(cmd);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.view().requestId()).isEqualTo("REQ-1");
        assertThat(requestRepo.count()).isEqualTo(1);
    }

    @Test
    void submitSameIdWithDifferentContentConflicts() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 5_000, 5_000, zone.id());
        submit("REQ-1", 2, 100, 100, 100, List.of(rack.id()));

        assertThatThrownBy(() -> service.submit(new Commands.SubmitRequest(
                "REQ-1", "srv", 4, 100, 100, 100, List.of(rack.id()))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void approveIsIdempotentWhenAlreadyApproved() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 5_000, 5_000, zone.id());
        submit("REQ-1", 2, 100, 100, 100, List.of(rack.id()));

        var first = service.approve("REQ-1", new Commands.Approve(0, null));
        var second = service.approve("REQ-1", new Commands.Approve(0, null));

        assertThat(second.reservation().id()).isEqualTo(first.reservation().id());
        assertThat(service.getRack(rack.id()).usedUnits()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // 版本控制
    // ------------------------------------------------------------------

    @Test
    void approveWithStaleRequestVersionFails() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 5_000, 5_000, zone.id());
        submit("REQ-1", 2, 100, 100, 100, List.of(rack.id()));
        // 申请内容变化 → 版本号从 0 变为 1
        service.updateRequest("REQ-1", new Commands.SubmitRequest(
                "REQ-1", "srv", 4, 100, 100, 100, List.of(rack.id())));

        assertThatThrownBy(() -> service.approve("REQ-1", new Commands.Approve(0, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // 基于新版本可以批准
        var view = service.approve("REQ-1", new Commands.Approve(1, null));
        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.reservation().uEnd()).isEqualTo(4);
    }

    @Test
    void approveWithStaleRackVersionFails() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 5_000, 5_000, zone.id());
        submit("REQ-1", 2, 100, 100, 100, List.of(rack.id()));
        long oldRackVersion = rack.version();

        // 容量配置变化 → 机柜版本号递增
        service.updateRack(rack.id(), new Commands.UpdateRack(null, 6_000L, null));

        assertThatThrownBy(() -> service.approve("REQ-1",
                new Commands.Approve(0, Map.of(rack.id(), oldRackVersion))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        long newRackVersion = service.getRack(rack.id()).version();
        var view = service.approve("REQ-1",
                new Commands.Approve(0, Map.of(rack.id(), newRackVersion)));
        assertThat(view.status()).isEqualTo("APPROVED");
    }

    @Test
    void rackCapacityCannotShrinkBelowReserved() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 5_000, 5_000, zone.id());
        submit("REQ-1", 2, 1_000, 100, 100, List.of(rack.id()));
        service.approve("REQ-1", new Commands.Approve(0, null));

        assertThatThrownBy(() -> service.updateRack(rack.id(),
                new Commands.UpdateRack(null, 500L, null)))
                .isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------------------
    // 迁移
    // ------------------------------------------------------------------

    @Test
    void migrateMovesReservationAndReleasesOldPosition() {
        var zone = zone("Z1", 10_000);
        var rack1 = rack("R1", 42, 5_000, 5_000, zone.id());
        var rack2 = rack("R2", 42, 5_000, 5_000, zone.id());
        submit("REQ-1", 4, 1_000, 800, 600, List.of(rack1.id()));
        service.approve("REQ-1", new Commands.Approve(0, null));

        var view = service.migrate("REQ-1", new Commands.Migrate(rack2.id(), null));

        assertThat(view.reservation().rackId()).isEqualTo(rack2.id());
        assertThat(service.getRack(rack1.id()).usedUnits()).isZero();
        assertThat(service.getRack(rack1.id()).feedAUsedW()).isZero();
        assertThat(service.getRack(rack2.id()).usedUnits()).isEqualTo(4);
        // 同区域迁移不改变区域制冷占用
        assertThat(service.getZone(zone.id()).usedCoolingW()).isEqualTo(600);
    }

    @Test
    void migrateFailureKeepsOriginalReservationIntact() {
        var zone = zone("Z1", 10_000);
        var rack1 = rack("R1", 42, 5_000, 5_000, zone.id());
        var rack2 = rack("R2", 42, 1_000, 1_000, zone.id()); // 目标机柜电力不足
        submit("REQ-1", 4, 2_000, 800, 600, List.of(rack1.id()));
        var approved = service.approve("REQ-1", new Commands.Approve(0, null));

        assertThatThrownBy(() -> service.migrate("REQ-1", new Commands.Migrate(rack2.id(), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));

        // 原预留保持不变
        var view = service.getRequest("REQ-1");
        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.reservation().id()).isEqualTo(approved.reservation().id());
        assertThat(view.reservation().rackId()).isEqualTo(rack1.id());
        assertThat(service.getRack(rack1.id()).usedUnits()).isEqualTo(4);
        assertThat(service.getRack(rack2.id()).usedUnits()).isZero();
    }

    // ------------------------------------------------------------------
    // 下架
    // ------------------------------------------------------------------

    @Test
    void decommissionReleasesEverythingAndIsIdempotent() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 5_000, 5_000, zone.id());
        submit("REQ-1", 4, 1_000, 800, 600, List.of(rack.id()));
        service.approve("REQ-1", new Commands.Approve(0, null));

        var first = service.decommission("REQ-1");

        assertThat(first.status()).isEqualTo("DECOMMISSIONED");
        var rackView = service.getRack(rack.id());
        assertThat(rackView.usedUnits()).isZero();
        assertThat(rackView.feedAUsedW()).isZero();
        assertThat(rackView.feedBUsedW()).isZero();
        assertThat(service.getZone(zone.id()).usedCoolingW()).isZero();

        // 重复下架返回首次结果
        var second = service.decommission("REQ-1");
        assertThat(second.decommissionedAt()).isEqualTo(first.decommissionedAt());
        assertThat(second.releasedReservationId()).isEqualTo(first.releasedReservationId());
    }

    @Test
    void decommissionOfPendingRequestConflicts() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 5_000, 5_000, zone.id());
        submit("REQ-1", 4, 1_000, 800, 600, List.of(rack.id()));

        assertThatThrownBy(() -> service.decommission("REQ-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ------------------------------------------------------------------
    // 事件查询
    // ------------------------------------------------------------------

    @Test
    void changeEventsAreRecordedAndQueryable() {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 42, 5_000, 5_000, zone.id());
        submit("REQ-1", 2, 100, 100, 100, List.of(rack.id()));
        service.approve("REQ-1", new Commands.Approve(0, null));
        service.decommission("REQ-1");

        var byRequest = service.listEvents("REQ-1", null, null);
        assertThat(byRequest).extracting(Views.EventView::type)
                .containsExactly("REQUEST_SUBMITTED", "REQUEST_APPROVED", "DEVICE_DECOMMISSIONED");

        var byRack = service.listEvents(null, rack.id(), null);
        assertThat(byRack).extracting(Views.EventView::type)
                .contains("RACK_CONFIG_UPDATED", "REQUEST_APPROVED", "DEVICE_DECOMMISSIONED");
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovalsNeverExceedAnyResourceLimit() throws Exception {
        var zone = zone("Z1", 10_000);
        var rack = rack("R1", 4, 10_000, 10_000, zone.id()); // 仅 4 个机位
        int contenders = 8;
        List<String> requestIds = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String requestId = "REQ-C" + i;
            submit(requestId, 1, 100, 100, 100, List.of(rack.id()));
            requestIds.add(requestId);
        }

        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (String requestId : requestIds) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.approve(requestId, new Commands.Approve(0, null));
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            }));
        }
        ready.await();
        start.countDown();

        int successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        pool.shutdown();

        // 8 个申请争抢 4 个机位：恰好 4 个成功，任何资源均未超限
        assertThat(successes).isEqualTo(4);
        var rackView = service.getRack(rack.id());
        assertThat(rackView.usedUnits()).isEqualTo(4);
        assertThat(rackView.usedUnits()).isLessThanOrEqualTo(rackView.totalUnits());
        assertThat(rackView.feedAUsedW()).isLessThanOrEqualTo(rackView.feedACapacityW());
        assertThat(rackView.feedBUsedW()).isLessThanOrEqualTo(rackView.feedBCapacityW());
        assertThat(service.getZone(zone.id()).usedCoolingW())
                .isLessThanOrEqualTo(zone.coolingCapacityW());
    }

    @Test
    void concurrentApprovalsRespectPowerLimit() throws Exception {
        var zone = zone("Z1", 10_000);
        // 机位充足；每路 3600W：每台主路 900W、备路 100W。
        // 前 4 台主路全部接 A 路（4×900=3600），此后 A 路连 100W 备路也放不下，
        // 而 B 路主路需要 A 路承接备路，同样不可行 → 恰好 4 台成功。
        var rack = rack("R1", 42, 3_600, 3_600, zone.id());
        int contenders = 8;
        List<String> requestIds = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String requestId = "REQ-P" + i;
            submit(requestId, 1, 900, 100, 100, List.of(rack.id()));
            requestIds.add(requestId);
        }

        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (String requestId : requestIds) {
            futures.add(pool.submit(() -> {
                try {
                    service.approve(requestId, new Commands.Approve(0, null));
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            }));
        }
        int successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        pool.shutdown();

        // 恰好 4 台成功，任一路电力均未超限
        assertThat(successes).isEqualTo(4);
        var rackView = service.getRack(rack.id());
        assertThat(rackView.feedAUsedW()).isEqualTo(3_600);
        assertThat(rackView.feedBUsedW()).isEqualTo(400);
        assertThat(rackView.feedAUsedW()).isLessThanOrEqualTo(rackView.feedACapacityW());
        assertThat(rackView.feedBUsedW()).isLessThanOrEqualTo(rackView.feedBCapacityW());
    }
}
