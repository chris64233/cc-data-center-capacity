package com.chris64233.datacentercapacity.web;

import com.chris64233.datacentercapacity.service.CapacityService;
import com.chris64233.datacentercapacity.web.Dtos.CreateZoneRequest;
import com.chris64233.datacentercapacity.web.Dtos.UpdateZoneRequest;
import com.chris64233.datacentercapacity.web.Dtos.ZoneView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/zones")
public class ZoneController {

    private final CapacityService service;

    public ZoneController(CapacityService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ZoneView create(@Valid @RequestBody CreateZoneRequest dto) {
        return service.createZone(dto);
    }

    @GetMapping("/{id}")
    public ZoneView get(@PathVariable long id) {
        return service.getZone(id);
    }

    @PutMapping("/{id}")
    public ZoneView update(@PathVariable long id, @Valid @RequestBody UpdateZoneRequest dto) {
        return service.updateZone(id, dto);
    }
}
