package com.chris64233.datacentercapacity.service;

import com.chris64233.datacentercapacity.api.Commands;
import com.chris64233.datacentercapacity.api.Views;
import com.chris64233.datacentercapacity.domain.ChangeEvent;
import com.chris64233.datacentercapacity.domain.CoolingZone;
import com.chris64233.datacentercapacity.domain.DeviceRequest;
import com.chris64233.datacentercapacity.domain.EventType;
import com.chris64233.datacentercapacity.domain.Feed;
import com.chris64233.datacentercapacity.domain.Rack;
import com.chris64233.datacentercapacity.domain.RequestStatus;
import com.chris64233.datacentercapacity.domain.Reservation;
import com.chris64233.datacentercapacity.domain.ReservationStatus;
import com.chris64233.datacentercapacity.repo.ChangeEventRepository;
import com.chris64233.datacentercapacity.repo.CoolingZoneRepository;
import com.chris64233.datacentercapacity.repo.DeviceRequestRepository;
import com.chris64233.datacentercapacity.repo.RackRepository;
import com.chris64233.datacentercapacity.repo.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 数据中心容量核心服务。
 *
 * <p>并发与一致性约定：
 * <ul>
 *   <li>所有资源预留/释放在同一事务内完成，任一资源不足即整体回滚；</li>
 *   <li>加锁顺序固定为「制冷区域（ID 升序）→ 机柜（ID 升序）」，避免死锁；</li>
 *   <li>申请、机柜、区域均带乐观版本号，基于旧版本的批准会被拒绝；</li>
 *   <li>主备电源始终分配到不同供电回路。</li>
 * </ul>
 */
@Service
public class CapacityService {

    private final CoolingZoneRepository zoneRepo;
    private final RackRepository rackRepo;
    private final DeviceRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final ChangeEventRepository eventRepo;

    public CapacityService(CoolingZoneRepository zoneRepo, RackRepository rackRepo,
                           DeviceRequestRepository requestRepo, ReservationRepository reservationRepo,
                           ChangeEventRepository eventRepo) {
        this.zoneRepo = zoneRepo;
        this.rackRepo = rackRepo;
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.eventRepo = eventRepo;
    }

    // ------------------------------------------------------------------
    // 制冷区域与机柜
    // ------------------------------------------------------------------

    @Transactional
    public Views.ZoneView createZone(Commands.CreateZone cmd) {
        zoneRepo.findByCode(cmd.code()).ifPresent(z -> {
            throw BusinessException.conflict("制冷区域编码已存在: " + cmd.code());
        });
        CoolingZone zone = zoneRepo.save(new CoolingZone(cmd.code(), cmd.coolingCapacityW()));
        event(EventType.ZONE_CONFIG_UPDATED, null, null, zone.getId(),
                "创建制冷区域 " + zone.getCode() + "，制冷容量 " + zone.getCoolingCapacityW() + "W");
        return zoneView(zone);
    }

    @Transactional
    public Views.ZoneView updateZone(long zoneId, Commands.UpdateZone cmd) {
        CoolingZone zone = zoneRepo.lockById(zoneId)
                .orElseThrow(() -> BusinessException.notFound("制冷区域不存在: " + zoneId));
        if (cmd.coolingCapacityW() != null) {
            long used = usedHeat(zoneId);
            if (cmd.coolingCapacityW() < used) {
                throw BusinessException.unprocessable(
                        "制冷容量不能低于当前已预留量 " + used + "W");
            }
            zone.setCoolingCapacityW(cmd.coolingCapacityW());
        }
        event(EventType.ZONE_CONFIG_UPDATED, null, null, zone.getId(),
                "更新制冷区域 " + zone.getCode() + "，制冷容量 " + zone.getCoolingCapacityW() + "W");
        return zoneView(zone);
    }

    @Transactional(readOnly = true)
    public Views.ZoneView getZone(long zoneId) {
        CoolingZone zone = zoneRepo.findById(zoneId)
                .orElseThrow(() -> BusinessException.notFound("制冷区域不存在: " + zoneId));
        return zoneView(zone);
    }

    @Transactional
    public Views.RackView createRack(Commands.CreateRack cmd) {
        rackRepo.findByCode(cmd.code()).ifPresent(r -> {
            throw BusinessException.conflict("机柜编码已存在: " + cmd.code());
        });
        CoolingZone zone = zoneRepo.findById(cmd.zoneId())
                .orElseThrow(() -> BusinessException.unprocessable("制冷区域不存在: " + cmd.zoneId()));
        Rack rack = rackRepo.save(new Rack(cmd.code(), cmd.totalUnits(),
                cmd.feedACapacityW(), cmd.feedBCapacityW(), zone));
        event(EventType.RACK_CONFIG_UPDATED, null, rack.getId(), zone.getId(),
                "创建机柜 " + rack.getCode() + "，机位 " + rack.getTotalUnits()
                        + "U，A路 " + rack.getFeedACapacityW() + "W，B路 " + rack.getFeedBCapacityW() + "W");
        return rackView(rack);
    }

    @Transactional
    public Views.RackView updateRack(long rackId, Commands.UpdateRack cmd) {
        Rack rack = rackRepo.lockById(rackId)
                .orElseThrow(() -> BusinessException.notFound("机柜不存在: " + rackId));
        List<Reservation> active = activeReservations(rack.getId());
        if (cmd.totalUnits() != null) {
            int maxOccupied = active.stream().mapToInt(Reservation::getUEnd).max().orElse(0);
            if (cmd.totalUnits() < maxOccupied) {
                throw BusinessException.unprocessable(
                        "机位数不能小于已占用的最高机位 " + maxOccupied);
            }
            rack.setTotalUnits(cmd.totalUnits());
        }
        if (cmd.feedACapacityW() != null) {
            long used = powerUsed(active, Feed.A);
            if (cmd.feedACapacityW() < used) {
                throw BusinessException.unprocessable("A路容量不能低于当前已预留量 " + used + "W");
            }
            rack.setFeedACapacityW(cmd.feedACapacityW());
        }
        if (cmd.feedBCapacityW() != null) {
            long used = powerUsed(active, Feed.B);
            if (cmd.feedBCapacityW() < used) {
                throw BusinessException.unprocessable("B路容量不能低于当前已预留量 " + used + "W");
            }
            rack.setFeedBCapacityW(cmd.feedBCapacityW());
        }
        event(EventType.RACK_CONFIG_UPDATED, null, rack.getId(), rack.getZone().getId(),
                "更新机柜 " + rack.getCode() + " 容量配置");
        return rackView(rack);
    }

    @Transactional(readOnly = true)
    public Views.RackView getRack(long rackId) {
        Rack rack = rackRepo.findById(rackId)
                .orElseThrow(() -> BusinessException.notFound("机柜不存在: " + rackId));
        return rackView(rack);
    }

    // ------------------------------------------------------------------
    // 设备申请
    // ------------------------------------------------------------------

    /**
     * 提交申请。requestId 为幂等键：内容相同的重复提交返回原申请；
     * 内容不同的同号提交视为冲突。
     */
    @Transactional
    public Views.SubmitResult submit(Commands.SubmitRequest cmd) {
        Optional<DeviceRequest> existing = requestRepo.findByRequestId(cmd.requestId());
        if (existing.isPresent()) {
            DeviceRequest req = existing.get();
            if (!contentMatches(req, cmd)) {
                throw BusinessException.conflict("申请号 " + cmd.requestId() + " 已存在且内容不同");
            }
            return new Views.SubmitResult(requestView(req), false);
        }
        validateRacksExist(cmd.allowedRackIds());
        DeviceRequest req = requestRepo.save(new DeviceRequest(cmd.requestId(), cmd.deviceName(),
                cmd.uHeight(), cmd.primaryPowerW(), cmd.backupPowerW(), cmd.heatLoadW(),
                cmd.allowedRackIds()));
        event(EventType.REQUEST_SUBMITTED, req.getRequestId(), null, null,
                "提交申请：机位 " + req.getUHeight() + "U，主路 " + req.getPrimaryPowerW()
                        + "W，备路 " + req.getBackupPowerW() + "W，散热 " + req.getHeatLoadW() + "W");
        return new Views.SubmitResult(requestView(req), true);
    }

    /** 修改申请内容（仅限待批准状态），版本号递增，此前基于旧版本的批准将失败。 */
    @Transactional
    public Views.RequestView updateRequest(String requestId, Commands.SubmitRequest cmd) {
        DeviceRequest req = requestRepo.lockByRequestId(requestId)
                .orElseThrow(() -> BusinessException.notFound("申请不存在: " + requestId));
        if (req.getStatus() != RequestStatus.PENDING) {
            throw BusinessException.conflict("仅待批准状态的申请可以修改内容");
        }
        validateRacksExist(cmd.allowedRackIds());
        req.setDeviceName(cmd.deviceName());
        req.setUHeight(cmd.uHeight());
        req.setPrimaryPowerW(cmd.primaryPowerW());
        req.setBackupPowerW(cmd.backupPowerW());
        req.setHeatLoadW(cmd.heatLoadW());
        req.setAllowedRackIds(cmd.allowedRackIds());
        event(EventType.REQUEST_UPDATED, requestId, null, null, "申请内容已变更，版本号递增");
        return requestView(req);
    }

    @Transactional(readOnly = true)
    public Views.RequestView getRequest(String requestId) {
        DeviceRequest req = requestRepo.findByRequestId(requestId)
                .orElseThrow(() -> BusinessException.notFound("申请不存在: " + requestId));
        return requestView(req);
    }

    /**
     * 批准申请：在同一事务内一次性预留机位、双路电力与制冷容量，
     * 任一资源不足即整体拒绝，不产生部分预留。
     */
    @Transactional
    public Views.RequestView approve(String requestId, Commands.Approve cmd) {
        DeviceRequest req = requestRepo.lockByRequestId(requestId)
                .orElseThrow(() -> BusinessException.notFound("申请不存在: " + requestId));
        if (req.getStatus() == RequestStatus.DECOMMISSIONED) {
            throw BusinessException.conflict("设备已下架，不能再批准");
        }
        if (req.getStatus() == RequestStatus.APPROVED) {
            // 幂等重试：已批准的申请直接返回当前预留（批准本身会使版本号递增，
            // 因此该判断必须先于版本校验）
            return requestView(req);
        }
        if (req.getVersion() != cmd.expectedVersion()) {
            throw BusinessException.conflict("申请内容已变更（当前版本 " + req.getVersion()
                    + "），基于旧版本的批准失败");
        }

        List<Rack> candidates = req.getAllowedRackIds().stream()
                .distinct().sorted()
                .map(id -> rackRepo.findById(id).orElseThrow(
                        () -> BusinessException.unprocessable("申请包含不存在的机柜: " + id)))
                .toList();
        checkRackVersions(candidates, cmd.expectedRackVersions());

        // 固定加锁顺序：先区域后机柜，均按 ID 升序
        Map<Long, CoolingZone> zones = lockZones(
                candidates.stream().map(r -> r.getZone().getId()));
        Map<Long, Long> zoneHeatUsed = new HashMap<>();
        zones.keySet().forEach(id -> zoneHeatUsed.put(id, usedHeat(id)));

        for (Rack candidate : candidates) {
            Rack rack = rackRepo.lockById(candidate.getId()).orElseThrow();
            CoolingZone zone = zones.get(rack.getZone().getId());
            if (zoneHeatUsed.get(zone.getId()) + req.getHeatLoadW() > zone.getCoolingCapacityW()) {
                continue; // 制冷不足，尝试下一机柜
            }
            List<Reservation> active = activeReservations(rack.getId());
            Optional<Placement> placement = findPlacement(rack, active, req.getUHeight(),
                    req.getPrimaryPowerW(), req.getBackupPowerW());
            if (placement.isEmpty()) {
                continue; // 机位或电力不足，尝试下一机柜
            }
            Placement p = placement.get();
            reservationRepo.save(new Reservation(req, rack, p.uStart(), p.uEnd(), p.primaryFeed(),
                    req.getPrimaryPowerW(), req.getBackupPowerW(), req.getHeatLoadW()));
            req.setStatus(RequestStatus.APPROVED);
            event(EventType.REQUEST_APPROVED, requestId, rack.getId(), zone.getId(),
                    "批准申请：机柜 " + rack.getCode() + " 机位 " + p.uStart() + "-" + p.uEnd()
                            + "，主路接 " + p.primaryFeed() + " 路，备路接 " + p.primaryFeed().other() + " 路");
            return requestView(req);
        }
        throw BusinessException.unprocessable("机位、电力或制冷容量不足，申请整体拒绝");
    }

    /**
     * 整体迁移预留：新位置的机位、双路电力与制冷容量全部锁定成功后才释放旧位置；
     * 任一步失败抛出异常并回滚，原预留保持不变。
     */
    @Transactional
    public Views.RequestView migrate(String requestId, Commands.Migrate cmd) {
        DeviceRequest req = requestRepo.lockByRequestId(requestId)
                .orElseThrow(() -> BusinessException.notFound("申请不存在: " + requestId));
        if (cmd.expectedVersion() != null && req.getVersion() != cmd.expectedVersion()) {
            throw BusinessException.conflict("申请内容已变更（当前版本 " + req.getVersion()
                    + "），基于旧版本的迁移失败");
        }
        if (req.getStatus() != RequestStatus.APPROVED) {
            throw BusinessException.conflict("仅已批准且未下架的预留可以迁移");
        }
        Reservation old = activeReservation(req)
                .orElseThrow(() -> BusinessException.conflict("没有可迁移的生效预留"));
        if (old.getRack().getId().equals(cmd.targetRackId())) {
            throw BusinessException.unprocessable("设备已在目标机柜，无需迁移");
        }
        Rack targetRef = rackRepo.findById(cmd.targetRackId())
                .orElseThrow(() -> BusinessException.notFound("目标机柜不存在: " + cmd.targetRackId()));

        // 固定加锁顺序：先区域后机柜，均按 ID 升序
        Map<Long, CoolingZone> zones = lockZones(Stream.of(
                old.getRack().getZone().getId(), targetRef.getZone().getId()));
        Map<Long, Rack> racks = new LinkedHashMap<>();
        Stream.of(old.getRack().getId(), targetRef.getId()).distinct().sorted()
                .forEach(id -> racks.put(id, rackRepo.lockById(id).orElseThrow()));
        Rack target = racks.get(targetRef.getId());

        CoolingZone targetZone = zones.get(target.getZone().getId());
        boolean sameZone = targetZone.getId().equals(old.getRack().getZone().getId());
        if (!sameZone && usedHeat(targetZone.getId()) + old.getHeatLoadW() > targetZone.getCoolingCapacityW()) {
            throw BusinessException.unprocessable("目标制冷区域容量不足，迁移失败，原预留保持不变");
        }
        List<Reservation> activeOnTarget = activeReservations(target.getId());
        Optional<Placement> placement = findPlacement(target, activeOnTarget, old.getUHeight(),
                old.getPrimaryPowerW(), old.getBackupPowerW());
        if (placement.isEmpty()) {
            throw BusinessException.unprocessable("目标机柜机位或电力不足，迁移失败，原预留保持不变");
        }

        // 新位置全部资源锁定成功，创建新预留并释放旧位置
        Placement p = placement.get();
        reservationRepo.save(new Reservation(req, target, p.uStart(), p.uEnd(), p.primaryFeed(),
                old.getPrimaryPowerW(), old.getBackupPowerW(), old.getHeatLoadW()));
        old.release(Reservation.REASON_MIGRATED);
        event(EventType.RESERVATION_MIGRATED, requestId, target.getId(), targetZone.getId(),
                "预留整体迁移：机柜 " + old.getRack().getCode() + " → " + target.getCode()
                        + " 机位 " + p.uStart() + "-" + p.uEnd());
        return requestView(req);
    }

    /**
     * 设备下架：一次性释放机位、双路电力与制冷容量。
     * 重复下架返回首次下架的结果（幂等）。
     */
    @Transactional
    public Views.DecommissionView decommission(String requestId) {
        DeviceRequest req = requestRepo.lockByRequestId(requestId)
                .orElseThrow(() -> BusinessException.notFound("申请不存在: " + requestId));
        if (req.getStatus() == RequestStatus.DECOMMISSIONED) {
            Long releasedId = reservationRepo.findByRequestIdOrderByIdDesc(req.getId()).stream()
                    .filter(r -> Reservation.REASON_DECOMMISSIONED.equals(r.getReleaseReason()))
                    .findFirst().map(Reservation::getId).orElse(null);
            return new Views.DecommissionView(requestId, req.getStatus().name(),
                    req.getDecommissionedAt(), releasedId);
        }
        if (req.getStatus() != RequestStatus.APPROVED) {
            throw BusinessException.conflict("申请尚未批准，无需下架");
        }
        Reservation active = activeReservation(req)
                .orElseThrow(() -> BusinessException.conflict("没有可释放的生效预留"));
        // 与批准/迁移保持相同的加锁顺序，串行化对同一资源的并发操作
        zoneRepo.lockById(active.getRack().getZone().getId());
        rackRepo.lockById(active.getRack().getId());
        active.release(Reservation.REASON_DECOMMISSIONED);
        req.setStatus(RequestStatus.DECOMMISSIONED);
        // 截断到微秒，与数据库时间精度对齐，保证重复下架返回完全一致的结果
        req.setDecommissionedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        event(EventType.DEVICE_DECOMMISSIONED, requestId, active.getRack().getId(),
                active.getRack().getZone().getId(),
                "设备下架，一次性释放机柜 " + active.getRack().getCode() + " 的机位、双路电力与制冷预留");
        return new Views.DecommissionView(requestId, req.getStatus().name(),
                req.getDecommissionedAt(), active.getId());
    }

    // ------------------------------------------------------------------
    // 变更事件查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Views.EventView> listEvents(String requestId, Long rackId, Long zoneId) {
        return eventRepo.findAllByOrderByIdAsc().stream()
                .filter(e -> requestId == null || requestId.equals(e.getRequestId()))
                .filter(e -> rackId == null || rackId.equals(e.getRackId()))
                .filter(e -> zoneId == null || zoneId.equals(e.getZoneId()))
                .map(this::eventView)
                .toList();
    }

    // ------------------------------------------------------------------
    // 内部：容量计算与放置
    // ------------------------------------------------------------------

    /** 一次可行的放置：连续机位段 + 主路所在回路（备路在另一回路上）。 */
    private record Placement(int uStart, int uEnd, Feed primaryFeed) {
    }

    /**
     * 在机柜上寻找可行放置：最低可用连续机位段，且主备电源分别接入不同回路后
     * 两路均不超限。机位可行但电力不足时直接失败（电力是机柜级资源，与机位无关）。
     */
    private Optional<Placement> findPlacement(Rack rack, List<Reservation> active,
                                              int uHeight, long primaryPowerW, long backupPowerW) {
        for (int start = 1; start + uHeight - 1 <= rack.getTotalUnits(); start++) {
            int end = start + uHeight - 1;
            if (overlaps(active, start, end)) {
                continue;
            }
            long feedAUsed = powerUsed(active, Feed.A);
            long feedBUsed = powerUsed(active, Feed.B);
            if (feedAUsed + primaryPowerW <= rack.getFeedACapacityW()
                    && feedBUsed + backupPowerW <= rack.getFeedBCapacityW()) {
                return Optional.of(new Placement(start, end, Feed.A));
            }
            if (feedBUsed + primaryPowerW <= rack.getFeedBCapacityW()
                    && feedAUsed + backupPowerW <= rack.getFeedACapacityW()) {
                return Optional.of(new Placement(start, end, Feed.B));
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private boolean overlaps(List<Reservation> active, int start, int end) {
        return active.stream().anyMatch(r -> start <= r.getUEnd() && r.getUStart() <= end);
    }

    private long powerUsed(List<Reservation> active, Feed feed) {
        return active.stream().mapToLong(r -> r.powerOn(feed)).sum();
    }

    private long usedHeat(long zoneId) {
        return reservationRepo.sumHeatByZoneIdAndStatus(zoneId, ReservationStatus.ACTIVE);
    }

    private List<Reservation> activeReservations(long rackId) {
        return reservationRepo.findByRackIdAndStatus(rackId, ReservationStatus.ACTIVE);
    }

    private Optional<Reservation> activeReservation(DeviceRequest req) {
        return reservationRepo
                .findByRequestIdAndStatusOrderByIdDesc(req.getId(), ReservationStatus.ACTIVE)
                .stream().findFirst();
    }

    /** 按 ID 升序对制冷区域加悲观写锁。 */
    private Map<Long, CoolingZone> lockZones(Stream<Long> zoneIds) {
        Map<Long, CoolingZone> zones = new LinkedHashMap<>();
        zoneIds.distinct().sorted().forEach(id -> zones.put(id, zoneRepo.lockById(id)
                .orElseThrow(() -> BusinessException.notFound("制冷区域不存在: " + id))));
        return zones;
    }

    private void checkRackVersions(List<Rack> candidates, Map<Long, Long> expectedRackVersions) {
        if (expectedRackVersions == null) {
            return;
        }
        for (Rack rack : candidates) {
            Long expected = expectedRackVersions.get(rack.getId());
            if (expected != null && rack.getVersion() != expected) {
                throw BusinessException.conflict("机柜 " + rack.getCode()
                        + " 容量配置已变更，基于旧版本的批准失败");
            }
        }
    }

    private void validateRacksExist(List<Long> rackIds) {
        for (Long id : new LinkedHashSet<>(rackIds)) {
            if (rackRepo.findById(id).isEmpty()) {
                throw BusinessException.unprocessable("申请包含不存在的机柜: " + id);
            }
        }
    }

    private boolean contentMatches(DeviceRequest req, Commands.SubmitRequest cmd) {
        return req.getUHeight() == cmd.uHeight()
                && req.getPrimaryPowerW() == cmd.primaryPowerW()
                && req.getBackupPowerW() == cmd.backupPowerW()
                && req.getHeatLoadW() == cmd.heatLoadW()
                && Objects.equals(req.getDeviceName(), cmd.deviceName())
                && new LinkedHashSet<>(req.getAllowedRackIds())
                        .equals(new LinkedHashSet<>(cmd.allowedRackIds()));
    }

    private void event(EventType type, String requestId, Long rackId, Long zoneId, String detail) {
        eventRepo.save(new ChangeEvent(type, requestId, rackId, zoneId, detail));
    }

    // ------------------------------------------------------------------
    // 内部：视图映射
    // ------------------------------------------------------------------

    private Views.ZoneView zoneView(CoolingZone zone) {
        long used = usedHeat(zone.getId());
        return new Views.ZoneView(zone.getId(), zone.getCode(), zone.getCoolingCapacityW(),
                used, zone.getCoolingCapacityW() - used, zone.getVersion());
    }

    private Views.RackView rackView(Rack rack) {
        List<Reservation> active = activeReservations(rack.getId());
        int usedUnits = active.stream().mapToInt(Reservation::getUHeight).sum();
        List<int[]> ranges = active.stream()
                .map(r -> new int[]{r.getUStart(), r.getUEnd()})
                .sorted(Comparator.comparingInt(a -> a[0]))
                .toList();
        return new Views.RackView(rack.getId(), rack.getCode(),
                rack.getZone().getId(), rack.getZone().getCode(),
                rack.getTotalUnits(), usedUnits, rack.getTotalUnits() - usedUnits,
                rack.getFeedACapacityW(), powerUsed(active, Feed.A),
                rack.getFeedBCapacityW(), powerUsed(active, Feed.B),
                ranges, rack.getVersion());
    }

    private Views.ReservationView reservationView(Reservation r) {
        return new Views.ReservationView(r.getId(), r.getRequest().getRequestId(),
                r.getRack().getId(), r.getRack().getCode(), r.getUStart(), r.getUEnd(),
                r.getPrimaryFeed().name(), r.getPrimaryFeed().other().name(),
                r.getPrimaryPowerW(), r.getBackupPowerW(), r.getHeatLoadW(),
                r.getStatus().name(), r.getCreatedAt(), r.getReleasedAt(), r.getReleaseReason());
    }

    private Views.RequestView requestView(DeviceRequest req) {
        Views.ReservationView reservation = activeReservation(req)
                .map(this::reservationView).orElse(null);
        return new Views.RequestView(req.getId(), req.getRequestId(), req.getDeviceName(),
                req.getUHeight(), req.getPrimaryPowerW(), req.getBackupPowerW(), req.getHeatLoadW(),
                List.copyOf(req.getAllowedRackIds()), req.getStatus().name(), req.getVersion(),
                req.getDecommissionedAt(), reservation);
    }

    private Views.EventView eventView(ChangeEvent e) {
        return new Views.EventView(e.getId(), e.getOccurredAt(), e.getType().name(),
                e.getRequestId(), e.getRackId(), e.getZoneId(), e.getDetail());
    }
}
