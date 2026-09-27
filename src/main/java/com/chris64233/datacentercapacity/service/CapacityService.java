package com.chris64233.datacentercapacity.service;

import com.chris64233.datacentercapacity.domain.ChangeEvent;
import com.chris64233.datacentercapacity.domain.CoolingZone;
import com.chris64233.datacentercapacity.domain.DeviceRequest;
import com.chris64233.datacentercapacity.domain.EventType;
import com.chris64233.datacentercapacity.domain.Feed;
import com.chris64233.datacentercapacity.domain.Rack;
import com.chris64233.datacentercapacity.domain.RequestStatus;
import com.chris64233.datacentercapacity.domain.Reservation;
import com.chris64233.datacentercapacity.domain.ReservationStatus;
import com.chris64233.datacentercapacity.repository.ChangeEventRepository;
import com.chris64233.datacentercapacity.repository.CoolingZoneRepository;
import com.chris64233.datacentercapacity.repository.DeviceRequestRepository;
import com.chris64233.datacentercapacity.repository.RackRepository;
import com.chris64233.datacentercapacity.repository.ReservationRepository;
import com.chris64233.datacentercapacity.web.Dtos.ApproveDto;
import com.chris64233.datacentercapacity.web.Dtos.CreateRackRequest;
import com.chris64233.datacentercapacity.web.Dtos.CreateZoneRequest;
import com.chris64233.datacentercapacity.web.Dtos.EventView;
import com.chris64233.datacentercapacity.web.Dtos.MigrateDto;
import com.chris64233.datacentercapacity.web.Dtos.RackView;
import com.chris64233.datacentercapacity.web.Dtos.RequestView;
import com.chris64233.datacentercapacity.web.Dtos.ReservationView;
import com.chris64233.datacentercapacity.web.Dtos.SubmitRequestDto;
import com.chris64233.datacentercapacity.web.Dtos.UpdateRackRequest;
import com.chris64233.datacentercapacity.web.Dtos.UpdateRequestDto;
import com.chris64233.datacentercapacity.web.Dtos.UpdateZoneRequest;
import com.chris64233.datacentercapacity.web.Dtos.ZoneView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 容量核心服务。
 *
 * 并发模型：预留/迁移/下架在事务内对机柜、制冷区域加悲观写锁（SELECT FOR UPDATE），
 * 锁顺序固定为先机柜（按 id 升序）后区域（按 id 升序），因此并发争抢不会超卖也不会死锁。
 * 申请、机柜、区域均带乐观版本号，基于旧版本的批准/变更被拒绝。
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
    // 制冷区域
    // ------------------------------------------------------------------

    @Transactional
    public ZoneView createZone(CreateZoneRequest dto) {
        zoneRepo.findByName(dto.name()).ifPresent(z -> {
            throw ApiException.conflict("ZONE_EXISTS", "制冷区域名称已存在: " + dto.name());
        });
        return zoneView(zoneRepo.save(new CoolingZone(dto.name(), dto.totalCoolingW())));
    }

    @Transactional(readOnly = true)
    public ZoneView getZone(long id) {
        return zoneView(zoneRepo.findById(id).orElseThrow(() -> ApiException.notFound("制冷区域")));
    }

    @Transactional
    public ZoneView updateZone(long id, UpdateZoneRequest dto) {
        CoolingZone zone = lockZone(id);
        checkVersion("制冷区域", zone.getVersion(), dto.expectedVersion());
        if (dto.totalCoolingW() < zone.getUsedCoolingW()) {
            throw ApiException.conflict("CAPACITY_BELOW_USED",
                    "制冷容量不能低于已预留量 " + zone.getUsedCoolingW() + "W");
        }
        zone.setTotalCoolingW(dto.totalCoolingW());
        record(EventType.ZONE_CONFIG_CHANGED, null, null, null, zone.getId(),
                "totalCoolingW=" + dto.totalCoolingW());
        return zoneView(zone);
    }

    // ------------------------------------------------------------------
    // 机柜
    // ------------------------------------------------------------------

    @Transactional
    public RackView createRack(CreateRackRequest dto) {
        rackRepo.findByName(dto.name()).ifPresent(r -> {
            throw ApiException.conflict("RACK_EXISTS", "机柜名称已存在: " + dto.name());
        });
        CoolingZone zone = zoneRepo.findById(dto.zoneId())
                .orElseThrow(() -> ApiException.notFound("制冷区域"));
        Rack rack = new Rack(dto.name(), dto.totalU(), dto.feedACapacityW(), dto.feedBCapacityW(), zone);
        return rackView(rackRepo.save(rack));
    }

    @Transactional(readOnly = true)
    public RackView getRack(long id) {
        return rackView(rackRepo.findById(id).orElseThrow(() -> ApiException.notFound("机柜")));
    }

    @Transactional
    public RackView updateRack(long id, UpdateRackRequest dto) {
        Rack rack = lockRack(id);
        checkVersion("机柜", rack.getVersion(), dto.expectedVersion());
        List<Reservation> active = reservationRepo.findActiveByRackId(id);
        if (dto.totalU() != null) {
            int maxUsedU = active.stream().mapToInt(r -> r.getUStart() + r.getUHeight() - 1).max().orElse(0);
            if (dto.totalU() < maxUsedU) {
                throw ApiException.conflict("CAPACITY_BELOW_USED",
                        "机位总数不能低于已占用的最高机位 " + maxUsedU);
            }
            rack.setTotalU(dto.totalU());
        }
        if (dto.feedACapacityW() != null) {
            if (dto.feedACapacityW() < rack.getUsedFeedAW()) {
                throw ApiException.conflict("CAPACITY_BELOW_USED",
                        "A 路容量不能低于已预留量 " + rack.getUsedFeedAW() + "W");
            }
            rack.setFeedACapacityW(dto.feedACapacityW());
        }
        if (dto.feedBCapacityW() != null) {
            if (dto.feedBCapacityW() < rack.getUsedFeedBW()) {
                throw ApiException.conflict("CAPACITY_BELOW_USED",
                        "B 路容量不能低于已预留量 " + rack.getUsedFeedBW() + "W");
            }
            rack.setFeedBCapacityW(dto.feedBCapacityW());
        }
        record(EventType.RACK_CONFIG_CHANGED, null, null, rack.getId(), rack.getZone().getId(),
                "totalU=" + rack.getTotalU() + " feedA=" + rack.getFeedACapacityW()
                        + "W feedB=" + rack.getFeedBCapacityW() + "W");
        return rackView(rack);
    }

    // ------------------------------------------------------------------
    // 设备申请
    // ------------------------------------------------------------------

    /** 提交申请。requestNo 幂等：内容一致返回已有申请，内容冲突返回 409。 */
    @Transactional
    public RequestView submitRequest(SubmitRequestDto dto) {
        var existing = requestRepo.findByRequestNo(dto.requestNo());
        if (existing.isPresent()) {
            DeviceRequest req = existing.get();
            if (req.contentEquals(dto.uHeight(), dto.primaryPowerW(), dto.backupPowerW(),
                    dto.heatLoadW(), dto.allowedRackIds())) {
                return requestView(req);
            }
            throw ApiException.conflict("REQUEST_NO_CONFLICT",
                    "申请号 " + dto.requestNo() + " 已存在且内容不一致");
        }
        validateRacksExist(dto.allowedRackIds());
        DeviceRequest req = new DeviceRequest(dto.requestNo(), dto.uHeight(), dto.primaryPowerW(),
                dto.backupPowerW(), dto.heatLoadW(), dto.allowedRackIds());
        req = requestRepo.save(req);
        record(EventType.REQUEST_CREATED, req.getRequestNo(), null, null, null,
                "uHeight=" + req.getUHeight() + " primary=" + req.getPrimaryPowerW()
                        + "W backup=" + req.getBackupPowerW() + "W heat=" + req.getHeatLoadW() + "W");
        return requestView(req);
    }

    @Transactional(readOnly = true)
    public RequestView getRequest(String requestNo) {
        return requestView(requestRepo.findByRequestNo(requestNo)
                .orElseThrow(() -> ApiException.notFound("设备申请")));
    }

    /** 修改申请内容（仅待批准状态），版本号递增，使基于旧版本的批准失败。 */
    @Transactional
    public RequestView updateRequest(String requestNo, UpdateRequestDto dto) {
        DeviceRequest req = requestRepo.findByRequestNo(requestNo)
                .orElseThrow(() -> ApiException.notFound("设备申请"));
        if (req.getStatus() != RequestStatus.PENDING) {
            throw ApiException.conflict("INVALID_STATE", "只有待批准的申请可以修改");
        }
        checkVersion("设备申请", req.getVersion(), dto.expectedVersion());
        validateRacksExist(dto.allowedRackIds());
        req.update(dto.uHeight(), dto.primaryPowerW(), dto.backupPowerW(), dto.heatLoadW(),
                dto.allowedRackIds());
        record(EventType.REQUEST_UPDATED, req.getRequestNo(), null, null, null,
                "uHeight=" + req.getUHeight() + " primary=" + req.getPrimaryPowerW()
                        + "W backup=" + req.getBackupPowerW() + "W heat=" + req.getHeatLoadW() + "W");
        return requestView(req);
    }

    // ------------------------------------------------------------------
    // 批准与预留
    // ------------------------------------------------------------------

    /**
     * 批准申请：在允许的机柜范围内找到第一个同时满足机位、双路电力、制冷的机柜，
     * 一次性预留全部资源；任一资源不足则整体拒绝，申请保持待批准。
     */
    @Transactional
    public ReservationView approve(String requestNo, ApproveDto dto) {
        DeviceRequest req = requestRepo.findByRequestNo(requestNo)
                .orElseThrow(() -> ApiException.notFound("设备申请"));
        if (req.getStatus() != RequestStatus.PENDING) {
            throw ApiException.conflict("INVALID_STATE", "申请 " + requestNo + " 不是待批准状态");
        }
        checkVersion("设备申请", req.getVersion(), dto.expectedRequestVersion());

        List<Long> candidates;
        if (dto.rackId() != null) {
            if (!req.getAllowedRackIds().contains(dto.rackId())) {
                throw ApiException.conflict("RACK_NOT_ALLOWED",
                        "机柜 " + dto.rackId() + " 不在申请允许的机柜范围内");
            }
            candidates = List.of(dto.rackId());
        } else {
            candidates = req.getAllowedRackIds().stream().sorted().toList();
        }

        List<String> shortages = new ArrayList<>();
        for (Long rackId : candidates) {
            Rack rack = lockRack(rackId);
            if (dto.expectedRackVersion() != null && rack.getVersion() != dto.expectedRackVersion()) {
                throw ApiException.stale("机柜 " + rack.getName(), dto.expectedRackVersion(), rack.getVersion());
            }
            CoolingZone zone = lockZone(rack.getZone().getId());
            Placement placement = tryPlace(rack, zone, req.getUHeight(), req.getPrimaryPowerW(),
                    req.getBackupPowerW(), req.getHeatLoadW(), null, dto.primaryFeed(), null, shortages);
            if (placement == null) {
                continue;
            }
            rack.addUsedFeedAW(placement.feedAPowerW);
            rack.addUsedFeedBW(placement.feedBPowerW);
            zone.addUsedCoolingW(req.getHeatLoadW());
            req.setStatus(RequestStatus.APPROVED);
            Reservation res = reservationRepo.save(new Reservation(req, rack, zone, placement.uStart,
                    req.getUHeight(), placement.primaryFeed, placement.feedAPowerW,
                    placement.feedBPowerW, req.getHeatLoadW()));
            record(EventType.RESERVED, req.getRequestNo(), res.getId(), rack.getId(), zone.getId(),
                    "机柜=" + rack.getName() + " 机位=" + res.getUStart() + "-" + (res.getUStart() + res.getUHeight() - 1)
                            + " 主路=" + res.getPrimaryFeed() + " A路=" + res.getFeedAPowerW()
                            + "W B路=" + res.getFeedBPowerW() + "W 制冷=" + res.getCoolingW() + "W");
            return reservationView(res);
        }
        throw ApiException.insufficient("资源不足，申请整体拒绝: " + String.join("; ", shortages));
    }

    // ------------------------------------------------------------------
    // 迁移 / 交付 / 下架
    // ------------------------------------------------------------------

    /**
     * 交付前整体迁移预留。新位置的机位、双路电力、制冷全部锁定成功后才释放旧位置；
     * 任一资源不足抛异常回滚，原预留保持不变。
     */
    @Transactional
    public ReservationView migrate(long reservationId, MigrateDto dto) {
        Reservation res = reservationRepo.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("预留"));
        if (res.getStatus() != ReservationStatus.RESERVED) {
            throw ApiException.conflict("INVALID_STATE", "只有已预留（未交付）的预留可以迁移");
        }
        if (dto.expectedVersion() != null && res.getVersion() != dto.expectedVersion()) {
            throw ApiException.stale("预留", dto.expectedVersion(), res.getVersion());
        }
        DeviceRequest req = res.getRequest();
        long oldRackId = res.getRack().getId();
        long newRackId = dto.targetRackId();

        // 固定锁顺序：先机柜（id 升序），后区域（id 升序）
        Rack oldRack;
        Rack newRack;
        if (oldRackId == newRackId) {
            oldRack = newRack = lockRack(oldRackId);
        } else if (oldRackId < newRackId) {
            oldRack = lockRack(oldRackId);
            newRack = lockRack(newRackId);
        } else {
            newRack = lockRack(newRackId);
            oldRack = lockRack(oldRackId);
        }
        long oldZoneId = oldRack.getZone().getId();
        long newZoneId = newRack.getZone().getId();
        CoolingZone oldZone;
        CoolingZone newZone;
        if (oldZoneId == newZoneId) {
            oldZone = newZone = lockZone(oldZoneId);
        } else if (oldZoneId < newZoneId) {
            oldZone = lockZone(oldZoneId);
            newZone = lockZone(newZoneId);
        } else {
            newZone = lockZone(newZoneId);
            oldZone = lockZone(oldZoneId);
        }

        List<String> shortages = new ArrayList<>();
        Placement placement = tryPlace(newRack, newZone, res.getUHeight(), req.getPrimaryPowerW(),
                req.getBackupPowerW(), req.getHeatLoadW(), res, dto.primaryFeed(), dto.uStart(), shortages);
        if (placement == null) {
            throw ApiException.insufficient("迁移目标资源不足，原预留保持不变: " + String.join("; ", shortages));
        }

        // 新位置全部资源锁定成功，释放旧位置并占用新位置
        oldRack.addUsedFeedAW(-res.getFeedAPowerW());
        oldRack.addUsedFeedBW(-res.getFeedBPowerW());
        oldZone.addUsedCoolingW(-res.getCoolingW());
        newRack.addUsedFeedAW(placement.feedAPowerW);
        newRack.addUsedFeedBW(placement.feedBPowerW);
        newZone.addUsedCoolingW(req.getHeatLoadW());

        String from = "机柜=" + oldRack.getName() + " 机位=" + res.getUStart()
                + "-" + (res.getUStart() + res.getUHeight() - 1);
        res.setRack(newRack);
        res.setZone(newZone);
        res.setUStart(placement.uStart);
        res.setPrimaryFeed(placement.primaryFeed);
        res.setFeedAPowerW(placement.feedAPowerW);
        res.setFeedBPowerW(placement.feedBPowerW);
        res.setCoolingW(req.getHeatLoadW());
        record(EventType.MIGRATED, req.getRequestNo(), res.getId(), newRack.getId(), newZone.getId(),
                from + " -> 机柜=" + newRack.getName() + " 机位=" + res.getUStart()
                        + "-" + (res.getUStart() + res.getUHeight() - 1)
                        + " 主路=" + res.getPrimaryFeed());
        return reservationView(res);
    }

    /** 交付。重复交付返回当前状态。 */
    @Transactional
    public ReservationView deliver(long reservationId) {
        Reservation res = reservationRepo.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("预留"));
        if (res.getStatus() == ReservationStatus.DELIVERED) {
            return reservationView(res);
        }
        if (res.getStatus() != ReservationStatus.RESERVED) {
            throw ApiException.conflict("INVALID_STATE", "已下架的预留不能交付");
        }
        res.setStatus(ReservationStatus.DELIVERED);
        res.setDeliveredAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        record(EventType.DELIVERED, res.getRequest().getRequestNo(), res.getId(),
                res.getRack().getId(), res.getZone().getId(), "交付完成");
        return reservationView(res);
    }

    /** 下架：一次性释放机位、双路电力和制冷。重复下架返回原结果。 */
    @Transactional
    public ReservationView decommission(long reservationId) {
        Reservation res = reservationRepo.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("预留"));
        if (res.getStatus() == ReservationStatus.RELEASED) {
            return reservationView(res);
        }
        Rack rack = lockRack(res.getRack().getId());
        CoolingZone zone = lockZone(res.getZone().getId());
        rack.addUsedFeedAW(-res.getFeedAPowerW());
        rack.addUsedFeedBW(-res.getFeedBPowerW());
        zone.addUsedCoolingW(-res.getCoolingW());
        res.setStatus(ReservationStatus.RELEASED);
        res.setReleasedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        record(EventType.RELEASED, res.getRequest().getRequestNo(), res.getId(),
                rack.getId(), zone.getId(),
                "下架释放 机柜=" + rack.getName() + " 机位=" + res.getUStart()
                        + "-" + (res.getUStart() + res.getUHeight() - 1)
                        + " A路=" + res.getFeedAPowerW() + "W B路=" + res.getFeedBPowerW()
                        + "W 制冷=" + res.getCoolingW() + "W");
        return reservationView(res);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ReservationView getReservation(long id) {
        return reservationView(reservationRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("预留")));
    }

    @Transactional(readOnly = true)
    public List<ReservationView> listReservations(String requestNo) {
        return reservationRepo.findByRequestRequestNoOrderByIdAsc(requestNo).stream()
                .map(this::reservationView).toList();
    }

    @Transactional(readOnly = true)
    public List<EventView> listEvents(String requestNo, EventType type) {
        List<ChangeEvent> events;
        if (requestNo != null && type != null) {
            events = eventRepo.findByRequestNoAndTypeOrderByIdAsc(requestNo, type);
        } else if (requestNo != null) {
            events = eventRepo.findByRequestNoOrderByIdAsc(requestNo);
        } else if (type != null) {
            events = eventRepo.findByTypeOrderByIdAsc(type);
        } else {
            events = eventRepo.findAllByOrderByIdAsc();
        }
        return events.stream().map(this::eventView).toList();
    }

    // ------------------------------------------------------------------
    // 内部：放置与校验
    // ------------------------------------------------------------------

    /** 一次放置尝试的结果：机位起点、主路回路及两路各自占用功率。 */
    private record Placement(int uStart, Feed primaryFeed, long feedAPowerW, long feedBPowerW) {
    }

    /**
     * 尝试在机柜上放置设备。机位需连续空闲；主备电源必须分路，
     * 优先按 preferredFeed（缺省 A 路）安放主路，放不下再交换。
     *
     * @param exclude 迁移场景下被排除的预留（同一机柜/区域时其占用不计入）
     */
    private Placement tryPlace(Rack rack, CoolingZone zone, int uHeight, long primaryPowerW,
                               long backupPowerW, long heatLoadW, Reservation exclude,
                               Feed preferredFeed, Integer preferredUStart, List<String> shortages) {
        long rackId = rack.getId();
        boolean excludeOnRack = exclude != null && exclude.isActive()
                && exclude.getRack().getId() == rackId;
        boolean excludeInZone = exclude != null && exclude.isActive()
                && exclude.getZone().getId() == zone.getId();

        Integer uStart = findUStart(rack, uHeight, exclude, preferredUStart);
        if (uStart == null) {
            shortages.add("机柜 " + rack.getName() + " 没有 " + uHeight + "U 连续空闲机位");
            return null;
        }

        long usedA = rack.getUsedFeedAW() - (excludeOnRack ? exclude.getFeedAPowerW() : 0);
        long usedB = rack.getUsedFeedBW() - (excludeOnRack ? exclude.getFeedBPowerW() : 0);
        Feed first = preferredFeed != null ? preferredFeed : Feed.A;
        Feed[] order = {first, first.other()};
        for (Feed primaryFeed : order) {
            long feedA = primaryFeed == Feed.A ? primaryPowerW : backupPowerW;
            long feedB = primaryFeed == Feed.A ? backupPowerW : primaryPowerW;
            if (usedA + feedA <= rack.getFeedACapacityW() && usedB + feedB <= rack.getFeedBCapacityW()) {
                long usedCooling = zone.getUsedCoolingW() - (excludeInZone ? exclude.getCoolingW() : 0);
                if (usedCooling + heatLoadW <= zone.getTotalCoolingW()) {
                    return new Placement(uStart, primaryFeed, feedA, feedB);
                }
                shortages.add("制冷区域 " + zone.getName() + " 制冷容量不足（剩余 "
                        + (zone.getTotalCoolingW() - usedCooling) + "W，需要 " + heatLoadW + "W）");
                return null;
            }
        }
        shortages.add("机柜 " + rack.getName() + " 电力容量不足（A 路剩余 "
                + (rack.getFeedACapacityW() - usedA) + "W，B 路剩余 "
                + (rack.getFeedBCapacityW() - usedB) + "W，需要主路 " + primaryPowerW
                + "W / 备路 " + backupPowerW + "W 且主备分路）");
        return null;
    }

    /** 找连续空闲机位：preferredUStart 非空时校验该起点，否则取最低可用起点。 */
    private Integer findUStart(Rack rack, int uHeight, Reservation exclude, Integer preferredUStart) {
        int totalU = rack.getTotalU();
        if (uHeight > totalU) {
            return null;
        }
        boolean[] used = new boolean[totalU + 2];
        for (Reservation r : reservationRepo.findActiveByRackId(rack.getId())) {
            if (exclude != null && exclude.getId().equals(r.getId())) {
                continue;
            }
            for (int u = r.getUStart(); u < r.getUStart() + r.getUHeight(); u++) {
                used[u] = true;
            }
        }
        if (preferredUStart != null) {
            if (preferredUStart < 1 || preferredUStart + uHeight - 1 > totalU) {
                return null;
            }
            for (int u = preferredUStart; u < preferredUStart + uHeight; u++) {
                if (used[u]) {
                    return null;
                }
            }
            return preferredUStart;
        }
        for (int start = 1; start + uHeight - 1 <= totalU; start++) {
            boolean free = true;
            for (int u = start; u < start + uHeight; u++) {
                if (used[u]) {
                    free = false;
                    break;
                }
            }
            if (free) {
                return start;
            }
        }
        return null;
    }

    private Rack lockRack(long id) {
        return rackRepo.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("机柜 " + id));
    }

    private CoolingZone lockZone(long id) {
        return zoneRepo.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("制冷区域 " + id));
    }

    private void checkVersion(String what, long actual, long expected) {
        if (actual != expected) {
            throw ApiException.stale(what, expected, actual);
        }
    }

    private void validateRacksExist(List<Long> rackIds) {
        for (Long rackId : rackIds) {
            if (!rackRepo.existsById(rackId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RACK_NOT_FOUND", "机柜 " + rackId + " 不存在");
            }
        }
    }

    private void record(EventType type, String requestNo, Long reservationId, Long rackId,
                        Long zoneId, String detail) {
        eventRepo.save(new ChangeEvent(type, requestNo, reservationId, rackId, zoneId, detail));
    }

    // ------------------------------------------------------------------
    // 视图映射
    // ------------------------------------------------------------------

    private ZoneView zoneView(CoolingZone z) {
        return new ZoneView(z.getId(), z.getName(), z.getTotalCoolingW(), z.getUsedCoolingW(),
                z.getTotalCoolingW() - z.getUsedCoolingW(), z.getVersion());
    }

    private RackView rackView(Rack r) {
        int usedU = reservationRepo.findActiveByRackId(r.getId()).stream()
                .mapToInt(Reservation::getUHeight).sum();
        return new RackView(r.getId(), r.getName(), r.getTotalU(), usedU, r.getTotalU() - usedU,
                r.getFeedACapacityW(), r.getUsedFeedAW(), r.getFeedACapacityW() - r.getUsedFeedAW(),
                r.getFeedBCapacityW(), r.getUsedFeedBW(), r.getFeedBCapacityW() - r.getUsedFeedBW(),
                r.getZone().getId(), r.getZone().getName(), r.getVersion());
    }

    private RequestView requestView(DeviceRequest r) {
        return new RequestView(r.getId(), r.getRequestNo(), r.getUHeight(), r.getPrimaryPowerW(),
                r.getBackupPowerW(), r.getHeatLoadW(), List.copyOf(r.getAllowedRackIds()),
                r.getStatus().name(), r.getVersion());
    }

    private ReservationView reservationView(Reservation r) {
        return new ReservationView(r.getId(), r.getRequest().getRequestNo(),
                r.getRack().getId(), r.getRack().getName(),
                r.getZone().getId(), r.getZone().getName(),
                r.getUStart(), r.getUHeight(), r.getPrimaryFeed(),
                r.getFeedAPowerW(), r.getFeedBPowerW(), r.getCoolingW(),
                r.getStatus().name(), r.getVersion(),
                r.getCreatedAt(), r.getDeliveredAt(), r.getReleasedAt());
    }

    private EventView eventView(ChangeEvent e) {
        return new EventView(e.getId(), e.getType().name(), e.getRequestNo(), e.getReservationId(),
                e.getRackId(), e.getZoneId(), e.getDetail(), e.getCreatedAt());
    }
}
