package com.chris64233.datacentercapacity;

import com.chris64233.datacentercapacity.repository.ChangeEventRepository;
import com.chris64233.datacentercapacity.repository.CoolingZoneRepository;
import com.chris64233.datacentercapacity.repository.DeviceRequestRepository;
import com.chris64233.datacentercapacity.repository.RackRepository;
import com.chris64233.datacentercapacity.repository.ReservationRepository;
import com.chris64233.datacentercapacity.service.CapacityService;
import com.chris64233.datacentercapacity.web.Dtos.ApproveDto;
import com.chris64233.datacentercapacity.web.Dtos.CreateRackRequest;
import com.chris64233.datacentercapacity.web.Dtos.CreateZoneRequest;
import com.chris64233.datacentercapacity.web.Dtos.RackView;
import com.chris64233.datacentercapacity.web.Dtos.RequestView;
import com.chris64233.datacentercapacity.web.Dtos.ReservationView;
import com.chris64233.datacentercapacity.web.Dtos.SubmitRequestDto;
import com.chris64233.datacentercapacity.web.Dtos.ZoneView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 并发争抢同一机柜/容量时，成功结果不得超过任一资源上限。 */
@SpringBootTest
class ConcurrencyTest {

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

    @Test
    void concurrentApprovalsNeverExceedPowerCapacity() throws InterruptedException {
        ZoneView z = service.createZone(new CreateZoneRequest("Z1", 100_000));
        // A、B 路各 1000W；每个申请主 400W / 备 400W → 最多 2 个成功
        RackView r = service.createRack(new CreateRackRequest("R1", 42, 1000, 1000, z.id()));
        int threads = 10;

        AtomicInteger successes = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        runConcurrently(threads, i -> {
            String no = "REQ-" + i;
            RequestView req = service.submitRequest(
                    new SubmitRequestDto(no, 1, 400, 400, 100, List.of(r.id())));
            try {
                service.approve(no, new ApproveDto(req.version(), null, null, null));
                successes.incrementAndGet();
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        assertThat(successes.get()).isEqualTo(2);
        assertThat(failures).hasSize(threads - 2);
        RackView after = service.getRack(r.id());
        assertThat(after.usedFeedAW()).isLessThanOrEqualTo(1000);
        assertThat(after.usedFeedBW()).isLessThanOrEqualTo(1000);
        assertThat(after.usedFeedAW()).isEqualTo(800);
    }

    @Test
    void concurrentApprovalsNeverOverlapUSpace() throws InterruptedException {
        ZoneView z = service.createZone(new CreateZoneRequest("Z1", 100_000));
        // 4U 机柜，每个申请 2U → 最多 2 个成功且机位不重叠
        RackView r = service.createRack(new CreateRackRequest("R1", 4, 100_000, 100_000, z.id()));
        int threads = 8;

        AtomicInteger successes = new AtomicInteger();
        runConcurrently(threads, i -> {
            String no = "REQ-" + i;
            RequestView req = service.submitRequest(
                    new SubmitRequestDto(no, 2, 100, 100, 100, List.of(r.id())));
            try {
                service.approve(no, new ApproveDto(req.version(), null, null, null));
                successes.incrementAndGet();
            } catch (Throwable ignored) {
            }
        });

        assertThat(successes.get()).isEqualTo(2);
        List<ReservationView> all = reservationRepo.findActiveByRackId(r.id()).stream()
                .map(res -> service.getReservation(res.getId())).toList();
        assertThat(all).hasSize(2);
        boolean[] used = new boolean[5];
        for (ReservationView res : all) {
            for (int u = res.uStart(); u < res.uStart() + res.uHeight(); u++) {
                assertThat(used[u]).as("机位 %d 被重复占用", u).isFalse();
                used[u] = true;
            }
        }
    }

    @Test
    void concurrentApprovalsNeverExceedCoolingCapacity() throws InterruptedException {
        // 区域制冷 1000W，每个申请散热 400W → 最多 2 个成功
        ZoneView z = service.createZone(new CreateZoneRequest("Z1", 1000));
        RackView r1 = service.createRack(new CreateRackRequest("R1", 42, 100_000, 100_000, z.id()));
        RackView r2 = service.createRack(new CreateRackRequest("R2", 42, 100_000, 100_000, z.id()));
        int threads = 6;

        AtomicInteger successes = new AtomicInteger();
        runConcurrently(threads, i -> {
            String no = "REQ-" + i;
            RequestView req = service.submitRequest(
                    new SubmitRequestDto(no, 1, 100, 100, 400, List.of(r1.id(), r2.id())));
            try {
                service.approve(no, new ApproveDto(req.version(), null, null, null));
                successes.incrementAndGet();
            } catch (Throwable ignored) {
            }
        });

        assertThat(successes.get()).isEqualTo(2);
        assertThat(service.getZone(z.id()).usedCoolingW()).isEqualTo(800);
    }

    private void runConcurrently(int threads, ThrowingTask task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run(idx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();
    }

    private interface ThrowingTask {
        void run(int index) throws InterruptedException;
    }
}
