package com.chris64233.datacentercapacity.web;

import com.chris64233.datacentercapacity.service.CapacityService;
import com.chris64233.datacentercapacity.web.Dtos.MigrateDto;
import com.chris64233.datacentercapacity.web.Dtos.ReservationView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final CapacityService service;

    public ReservationController(CapacityService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ReservationView get(@PathVariable long id) {
        return service.getReservation(id);
    }

    @GetMapping
    public List<ReservationView> listByRequest(@RequestParam String requestNo) {
        return service.listReservations(requestNo);
    }

    /** 交付前整体迁移预留：新位置全部资源锁定成功后才释放旧位置。 */
    @PostMapping("/{id}/migrate")
    public ReservationView migrate(@PathVariable long id, @Valid @RequestBody MigrateDto dto) {
        return service.migrate(id, dto);
    }

    @PostMapping("/{id}/deliver")
    public ReservationView deliver(@PathVariable long id) {
        return service.deliver(id);
    }

    /** 下架并一次性释放全部资源，重复下架返回原结果。 */
    @PostMapping("/{id}/decommission")
    public ReservationView decommission(@PathVariable long id) {
        return service.decommission(id);
    }
}
