package com.chris64233.datacentercapacity.web;

import com.chris64233.datacentercapacity.service.CapacityService;
import com.chris64233.datacentercapacity.web.Dtos.CreateRackRequest;
import com.chris64233.datacentercapacity.web.Dtos.RackView;
import com.chris64233.datacentercapacity.web.Dtos.UpdateRackRequest;
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
@RequestMapping("/api/racks")
public class RackController {

    private final CapacityService service;

    public RackController(CapacityService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RackView create(@Valid @RequestBody CreateRackRequest dto) {
        return service.createRack(dto);
    }

    @GetMapping("/{id}")
    public RackView get(@PathVariable long id) {
        return service.getRack(id);
    }

    @PutMapping("/{id}")
    public RackView update(@PathVariable long id, @Valid @RequestBody UpdateRackRequest dto) {
        return service.updateRack(id, dto);
    }
}
