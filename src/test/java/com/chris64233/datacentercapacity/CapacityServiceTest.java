package com.chris64233.datacentercapacity;

import com.chris64233.datacentercapacity.domain.EventType;
import com.chris64233.datacentercapacity.domain.Feed;
import com.chris64233.datacentercapacity.repository.ChangeEventRepository;
import com.chris64233.datacentercapacity.repository.CoolingZoneRepository;
import com.chris64233.datacentercapacity.repository.DeviceRequestRepository;
import com.chris64233.datacentercapacity.repository.RackRepository;
import com.chris64233.datacentercapacity.repository.ReservationRepository;
import com.chris64233.datacentercapacity.service.ApiException;
import com.chris64233.datacentercapacity.service.CapacityService;
import com.chris64233.datacentercapacity.web.Dtos.ApproveDto;
import com.chris64233.datacentercapacity.web.Dtos.CreateRackRequest;
import com.chris64233.datacentercapacity.web.Dtos.CreateZoneRequest;
import com.chris64233.datacentercapacity.web.Dtos.MigrateDto;
import com.chris64233.datacentercapacity.web.Dtos.RackView;
import com.chris64233.datacentercapacity.web.Dtos.RequestView;
import com.chris64233.datacentercapacity.web.Dtos.ReservationView;
import com.chris64233.datacentercapacity.web.Dtos.SubmitRequestDto;
import com.chris64233.datacentercapacity.web.Dtos.UpdateRackRequest;
import com.chris64233.datacentercapacity.web.Dtos.UpdateRequestDto;
import com.chris64233.datacentercapacity.web.Dtos.UpdateZoneRequest;
import com.chris64233.datacentercapacity.web.Dtos.ZoneView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CapacityServiceTest {

    @Autowired
    CapacityService service;
    @Autowired
    ChangeEventRepository eventRepo;
    @Autowired
    ReservationRepository reservationRepo;
    @Autowired
    DeviceRequestRepository requestRepo;
    @Autowired
    RackRepository rackRepo;
    @Autowired
    CoolingZoneRepository zoneRepo;

    @BeforeEach
    void clean() {
        eventRepo.deleteAll();
        reservationRepo.deleteAll();
        requestRepo.deleteAll();
        rackRepo.deleteAll();
        zoneRepo.deleteAll();
    }

    private ZoneView zone(String name, long coolingW) {
        return service.createZone(new CreateZoneRequest(name, coolingW));
    }

    private RackView rack(String name, int totalU, long feedA, long feedB, long zoneId) {
        return service.createRack(new CreateRackRequest(name, totalU, feedA, feedB, zoneId));
    }

    private RequestView request(String no, int uHeight, long primary, long backup, long heat, List<Long> rackIds) {
        return service.submitRequest(new SubmitRequestDto(no, uHeight, primary, backup, heat, rackIds));
    }

    // ------------------------------------------------------------------
    // 一次性预留
    // ------------------------------------------------------------------

    @Test
    void approveReservesSpacePowerAndCoolingAtomically() {
        ZoneView z = zone("Z1", 5000);
        RackView r = rack("R1", 42, 3000, 3000, z.id());
        RequestView req = request("REQ-1", 4, 400, 200, 350, List.of(r.id()));

        ReservationView res = service.approve("REQ-1", new ApproveDto(req.version(), null, null, null));

        assertThat(res.status()).isEqualTo("RESERVED");
        assertThat(res.rackId()).isEqualTo(r.id());
        assertThat(res.uStart()).isEqualTo(1);
        assertThat(res.uHeight()).isEqualTo(4);
        assertThat(res.coolingW()).isEqualTo(350);

        RackView rackAfter = service.getRack(r.id());
        assertThat(rackAfter.usedFeedAW()).isEqualTo(400);
        assertThat(rackAfter.usedFeedBW()).isEqualTo(200);
        assertThat(rackAfter.usedU()).isEqualTo(4);
        ZoneView zoneAfter = service.getZone(z.id());
        assertThat(zoneAfter.usedCoolingW()).isEqualTo(350);
        assertThat(service.getRequest("REQ-1").status()).isEqualTo("APPROVED");

        assertThat(service.listEvents("REQ-1", EventType.RESERVED)).hasSize(1);
    }

    @Test
    void primaryAndBackupNeverShareTheSameFeed() {
        ZoneView z = zone("Z1", 5000);
        RackView r = rack("R1", 42, 3000, 3000, z.id());
        RequestView req = request("REQ-1", 2, 400, 200, 300, List.of(r.id()));

        ReservationView res = service.approve("REQ-1", new ApproveDto(req.version(), null, null, null));

        // 主路 400W 与备路 200W 落在不同回路上
        if (res.primaryFeed() == Feed.A) {
            assertThat(res.feedAPowerW()).isEqualTo(400);
            assertThat(res.feedBPowerW()).isEqualTo(200);
        } else {
            assertThat(res.primaryFeed()).isEqualTo(Feed.B);
            assertThat(res.feedBPowerW()).isEqualTo(400);
            assertThat(res.feedAPowerW()).isEqualTo(200);
        }
    }

    @Test
    void fallsBackToSwappedFeedsWhenPrimaryDoesNotFitOnA() {
        ZoneView z = zone("Z1", 5000);
        // A 路只剩 300W 空间，主路 400W 放不下，应交换为主路走 B
        RackView r = rack("R1", 42, 700, 3000, z.id());
        RequestView first = request("REQ-0", 1, 400, 0, 0, List.of(r.id()));
        service.approve("REQ-0", new ApproveDto(first.version(), null, null, null));

        RequestView req = request("REQ-1", 2, 400, 200, 300, List.of(r.id()));
        ReservationView res = service.approve("REQ-1", new ApproveDto(req.version(), null, null, null));

        assertThat(res.primaryFeed()).isEqualTo(Feed.B);
        assertThat(res.feedBPowerW()).isEqualTo(400);
        assertThat(res.feedAPowerW()).isEqualTo(200);
    }

    @Test
    void insufficientPowerRejectsWholeRequestWithoutPartialReservation() {
        ZoneView z = zone("Z1", 5000);
        RackView r = rack("R1", 42, 300, 300, z.id()); // 两路都不够
        RequestView req = request("REQ-1", 4, 400, 200, 350, List.of(r.id()));

        assertThatThrownBy(() -> service.approve("REQ-1", new ApproveDto(req.version(), null, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("INSUFFICIENT_CAPACITY");
                });

        // 整体拒绝：无任何资源被占用，申请保持待批准
        RackView rackAfter = service.getRack(r.id());
        assertThat(rackAfter.usedFeedAW()).isZero();
        assertThat(rackAfter.usedFeedBW()).isZero();
        assertThat(rackAfter.usedU()).isZero();
        assertThat(service.getZone(z.id()).usedCoolingW()).isZero();
        assertThat(service.getRequest("REQ-1").status()).isEqualTo("PENDING");
        assertThat(service.listReservations("REQ-1")).isEmpty();
    }

    @Test
    void insufficientCoolingRejectsWholeRequest() {
        ZoneView z = zone("Z1", 300); // 制冷不够
        RackView r = rack("R1", 42, 3000, 3000, z.id());
        RequestView req = request("REQ-1", 2, 400, 200, 350, List.of(r.id()));

        assertThatThrownBy(() -> service.approve("REQ-1", new ApproveDto(req.version(), null, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INSUFFICIENT_CAPACITY"));
        assertThat(service.getRack(r.id()).usedU()).isZero();
        assertThat(service.getRequest("REQ-1").status()).isEqualTo("PENDING");
    }

    @Test
    void noContiguousSpaceRejectsWholeRequest() {
        ZoneView z = zone("Z1", 10000);
        RackView r = rack("R1", 4, 3000, 3000, z.id());
        // 占 1U 和 3U，剩 2U、4U 不连续，无法容纳 2U 设备
        RequestView r1 = request("REQ-1", 1, 100, 100, 100, List.of(r.id()));
        service.approve("REQ-1", new ApproveDto(r1.version(), r.id(), null, null));
        RequestView r2 = request("REQ-2", 1, 100, 100, 100, List.of(r.id()));
        service.approve("REQ-2", new ApproveDto(r2.version(), r.id(), null, null));
        // 手动占掉 3U：通过迁移把 REQ-2 挪到 3U
        ReservationView res2 = service.listReservations("REQ-2").get(0);
        service.migrate(res2.id(), new MigrateDto(r.id(), 3, null, null));

        RequestView r3 = request("REQ-3", 2, 100, 100, 100, List.of(r.id()));
        assertThatThrownBy(() -> service.approve("REQ-3", new ApproveDto(r3.version(), null, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INSUFFICIENT_CAPACITY"));
    }

    @Test
    void approveUsesNextAllowedRackWhenFirstIsFull() {
        ZoneView z = zone("Z1", 10000);
        RackView r1 = rack("R1", 2, 100, 100, z.id());  // 放不下
        RackView r2 = rack("R2", 42, 3000, 3000, z.id());
        RequestView req = request("REQ-1", 4, 400, 200, 300, List.of(r1.id(), r2.id()));

        ReservationView res = service.approve("REQ-1", new ApproveDto(req.version(), null, null, null));

        assertThat(res.rackId()).isEqualTo(r2.id());
    }

    // ------------------------------------------------------------------
    // 幂等与版本
    // ------------------------------------------------------------------

    @Test
    void submitIsIdempotentByRequestNo() {
        ZoneView z = zone("Z1", 5000);
        RackView r = rack("R1", 42, 3000, 3000, z.id());
        var dto = new SubmitRequestDto("REQ-1", 2, 400, 200, 300, List.of(r.id()));

        RequestView first = service.submitRequest(dto);
        RequestView second = service.submitRequest(dto);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.version()).isEqualTo(first.version());

        // 同申请号不同内容 → 冲突
        var changed = new SubmitRequestDto("REQ-1", 4, 400, 200, 300, List.of(r.id()));
        assertThatThrownBy(() -> service.submitRequest(changed))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REQUEST_NO_CONFLICT"));
    }

    @Test
    void approveWithStaleRequestVersionFails() {
        ZoneView z = zone("Z1", 5000);
        RackView r = rack("R1", 42, 3000, 3000, z.id());
        RequestView req = request("REQ-1", 2, 400, 200, 300, List.of(r.id()));

        // 申请内容变化 → 版本递增
        service.updateRequest("REQ-1",
                new UpdateRequestDto(req.version(), 4, 400, 200, 300, List.of(r.id())));

        // 基于旧版本的批准失败
        assertThatThrownBy(() -> service.approve("REQ-1", new ApproveDto(req.version(), null, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STALE_VERSION"));
        assertThat(service.getRequest("REQ-1").status()).isEqualTo("PENDING");
    }

    @Test
    void approveWithStaleRackVersionFails() {
        ZoneView z = zone("Z1", 5000);
        RackView r = rack("R1", 42, 3000, 3000, z.id());
        RequestView req = request("REQ-1", 2, 400, 200, 300, List.of(r.id()));

        // 机柜容量配置变化 → 版本递增
        service.updateRack(r.id(), new UpdateRackRequest(r.version(), null, 5000L, null));

        // 基于旧机柜版本的批准失败
        assertThatThrownBy(() -> service.approve("REQ-1",
                new ApproveDto(req.version(), r.id(), r.version(), null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STALE_VERSION"));

        // 使用新版本则成功
        RackView current = service.getRack(r.id());
        ReservationView res = service.approve("REQ-1",
                new ApproveDto(req.version(), r.id(), current.version(), null));
        assertThat(res.status()).isEqualTo("RESERVED");
    }

    @Test
    void configUpdateWithStaleVersionFails() {
        ZoneView z = zone("Z1", 5000);
        service.updateZone(z.id(), new UpdateZoneRequest(z.version(), 6000));
        assertThatThrownBy(() -> service.updateZone(z.id(), new UpdateZoneRequest(z.version(), 7000)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("STALE_VERSION"));
    }

    @Test
    void configCannotShrinkBelowUsed() {
        ZoneView z = zone("Z1", 5000);
        RackView r = rack("R1", 42, 3000, 3000, z.id());
        RequestView req = request("REQ-1", 2, 400, 200, 300, List.of(r.id()));
        service.approve("REQ-1", new ApproveDto(req.version(), null, null, null));

        RackView current = service.getRack(r.id());
        assertThatThrownBy(() -> service.updateRack(r.id(),
                new UpdateRackRequest(current.version(), null, 300L, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CAPACITY_BELOW_USED"));
    }

    // ------------------------------------------------------------------
    // 迁移
    // ------------------------------------------------------------------

    @Test
    void migrateMovesWholeReservationToNewLocation() {
        ZoneView z1 = zone("Z1", 5000);
        ZoneView z2 = zone("Z2", 5000);
        RackView r1 = rack("R1", 42, 3000, 3000, z1.id());
        RackView r2 = rack("R2", 42, 3000, 3000, z2.id());
        RequestView req = request("REQ-1", 4, 400, 200, 350, List.of(r1.id()));
        ReservationView res = service.approve("REQ-1", new ApproveDto(req.version(), null, null, null));

        ReservationView moved = service.migrate(res.id(), new MigrateDto(r2.id(), null, null, null));

        assertThat(moved.rackId()).isEqualTo(r2.id());
        assertThat(moved.zoneId()).isEqualTo(z2.id());
        assertThat(moved.status()).isEqualTo("RESERVED");
        // 旧位置已释放，新位置已占用
        assertThat(service.getRack(r1.id()).usedFeedAW()).isZero();
        assertThat(service.getZone(z1.id()).usedCoolingW()).isZero();
        assertThat(service.getRack(r2.id()).usedFeedAW()).isEqualTo(400);
        assertThat(service.getRack(r2.id()).usedU()).isEqualTo(4);
        assertThat(service.getZone(z2.id()).usedCoolingW()).isEqualTo(350);
        assertThat(service.listEvents("REQ-1", EventType.MIGRATED)).hasSize(1);
    }

    @Test
    void failedMigrationKeepsOriginalReservationUntouched() {
        ZoneView z1 = zone("Z1", 5000);
        ZoneView z2 = zone("Z2", 100); // 目标区域制冷不足
        RackView r1 = rack("R1", 42, 3000, 3000, z1.id());
        RackView r2 = rack("R2", 42, 3000, 3000, z2.id());
        RequestView req = request("REQ-1", 4, 400, 200, 350, List.of(r1.id()));
        ReservationView res = service.approve("REQ-1", new ApproveDto(req.version(), null, null, null));

        assertThatThrownBy(() -> service.migrate(res.id(), new MigrateDto(r2.id(), null, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INSUFFICIENT_CAPACITY"));

        // 原预留保持不变
        ReservationView after = service.getReservation(res.id());
        assertThat(after.rackId()).isEqualTo(r1.id());
        assertThat(after.uStart()).isEqualTo(res.uStart());
        assertThat(after.status()).isEqualTo("RESERVED");
        assertThat(service.getRack(r1.id()).usedFeedAW()).isEqualTo(400);
        assertThat(service.getZone(z1.id()).usedCoolingW()).isEqualTo(350);
        assertThat(service.getRack(r2.id()).usedU()).isZero();
        assertThat(service.getZone(z2.id()).usedCoolingW()).isZero();
    }

    @Test
    void cannotMigrateAfterDelivery() {
        ZoneView z = zone("Z1", 10000);
        RackView r1 = rack("R1", 42, 3000, 3000, z.id());
        RackView r2 = rack("R2", 42, 3000, 3000, z.id());
        RequestView req = request("REQ-1", 2, 400, 200, 300, List.of(r1.id()));
        ReservationView res = service.approve("REQ-1", new ApproveDto(req.version(), null, null, null));
        service.deliver(res.id());

        assertThatThrownBy(() -> service.migrate(res.id(), new MigrateDto(r2.id(), null, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_STATE"));
    }

    // ------------------------------------------------------------------
    // 交付与下架
    // ------------------------------------------------------------------

    @Test
    void decommissionReleasesAllResourcesAndIsIdempotent() {
        ZoneView z = zone("Z1", 5000);
        RackView r = rack("R1", 42, 3000, 3000, z.id());
        RequestView req = request("REQ-1", 4, 400, 200, 350, List.of(r.id()));
        ReservationView res = service.approve("REQ-1", new ApproveDto(req.version(), null, null, null));
        service.deliver(res.id());

        ReservationView released = service.decommission(res.id());
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(released.releasedAt()).isNotNull();

        // 全部资源一次性释放
        RackView rackAfter = service.getRack(r.id());
        assertThat(rackAfter.usedFeedAW()).isZero();
        assertThat(rackAfter.usedFeedBW()).isZero();
        assertThat(rackAfter.usedU()).isZero();
        assertThat(service.getZone(z.id()).usedCoolingW()).isZero();

        // 重复下架返回原结果，不重复扣减
        ReservationView again = service.decommission(res.id());
        assertThat(again.status()).isEqualTo("RELEASED");
        assertThat(again.releasedAt()).isEqualTo(released.releasedAt());
        assertThat(service.getRack(r.id()).usedFeedAW()).isZero();
        assertThat(service.listEvents("REQ-1", EventType.RELEASED)).hasSize(1);
    }

    @Test
    void releasedSpaceCanBeReused() {
        ZoneView z = zone("Z1", 5000);
        RackView r = rack("R1", 2, 500, 500, z.id());
        RequestView r1 = request("REQ-1", 2, 400, 200, 300, List.of(r.id()));
        ReservationView res1 = service.approve("REQ-1", new ApproveDto(r1.version(), null, null, null));
        service.decommission(res1.id());

        RequestView r2 = request("REQ-2", 2, 400, 200, 300, List.of(r.id()));
        ReservationView res2 = service.approve("REQ-2", new ApproveDto(r2.version(), null, null, null));
        assertThat(res2.uStart()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Test
    void queriesExposeReservationsRackZoneAndEvents() {
        ZoneView z = zone("Z1", 5000);
        RackView r = rack("R1", 42, 3000, 3000, z.id());
        RequestView req = request("REQ-1", 2, 400, 200, 300, List.of(r.id()));
        ReservationView res = service.approve("REQ-1", new ApproveDto(req.version(), null, null, null));

        assertThat(service.listReservations("REQ-1")).hasSize(1);
        assertThat(service.getReservation(res.id()).requestNo()).isEqualTo("REQ-1");
        assertThat(service.getRack(r.id()).freeU()).isEqualTo(40);
        assertThat(service.getZone(z.id()).freeCoolingW()).isEqualTo(4700);

        var events = service.listEvents("REQ-1", null);
        assertThat(events).extracting("type")
                .containsExactly(EventType.REQUEST_CREATED.name(), EventType.RESERVED.name());
    }
}
