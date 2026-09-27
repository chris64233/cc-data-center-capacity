package com.chris64233.datacentercapacity.web;

import com.chris64233.datacentercapacity.domain.EventType;
import com.chris64233.datacentercapacity.service.CapacityService;
import com.chris64233.datacentercapacity.web.Dtos.EventView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final CapacityService service;

    public EventController(CapacityService service) {
        this.service = service;
    }

    @GetMapping
    public List<EventView> list(@RequestParam(required = false) String requestNo,
                                @RequestParam(required = false) EventType type) {
        return service.listEvents(requestNo, type);
    }
}
