package com.chris64233.datacentercapacity.web;

import com.chris64233.datacentercapacity.api.Commands;
import com.chris64233.datacentercapacity.api.Views;
import com.chris64233.datacentercapacity.service.CapacityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 制冷区域、机柜资源与变更事件 API。 */
@RestController
@RequestMapping("/api")
public class InventoryController {

    private final CapacityService service;

    public InventoryController(CapacityService service) {
        this.service = service;
    }

    @PostMapping("/zones")
    public ResponseEntity<Views.ZoneView> createZone(@Valid @RequestBody Commands.CreateZone cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createZone(cmd));
    }

    @GetMapping("/zones/{id}")
    public Views.ZoneView getZone(@PathVariable long id) {
        return service.getZone(id);
    }

    @PatchMapping("/zones/{id}")
    public Views.ZoneView updateZone(@PathVariable long id,
                                     @Valid @RequestBody Commands.UpdateZone cmd) {
        return service.updateZone(id, cmd);
    }

    @PostMapping("/racks")
    public ResponseEntity<Views.RackView> createRack(@Valid @RequestBody Commands.CreateRack cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createRack(cmd));
    }

    @GetMapping("/racks/{id}")
    public Views.RackView getRack(@PathVariable long id) {
        return service.getRack(id);
    }

    @PatchMapping("/racks/{id}")
    public Views.RackView updateRack(@PathVariable long id,
                                     @Valid @RequestBody Commands.UpdateRack cmd) {
        return service.updateRack(id, cmd);
    }

    @GetMapping("/events")
    public List<Views.EventView> listEvents(@RequestParam(required = false) String requestId,
                                            @RequestParam(required = false) Long rackId,
                                            @RequestParam(required = false) Long zoneId) {
        return service.listEvents(requestId, rackId, zoneId);
    }
}
