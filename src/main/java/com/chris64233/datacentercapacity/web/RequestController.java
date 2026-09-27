package com.chris64233.datacentercapacity.web;

import com.chris64233.datacentercapacity.api.Commands;
import com.chris64233.datacentercapacity.api.Views;
import com.chris64233.datacentercapacity.service.CapacityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 设备申请：提交（幂等）、批准预留、整体迁移、下架释放。 */
@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final CapacityService service;

    public RequestController(CapacityService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Views.RequestView> submit(@Valid @RequestBody Commands.SubmitRequest cmd) {
        Views.SubmitResult result = service.submit(cmd);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.view());
    }

    @GetMapping("/{requestId}")
    public Views.RequestView get(@PathVariable String requestId) {
        return service.getRequest(requestId);
    }

    @PutMapping("/{requestId}")
    public Views.RequestView update(@PathVariable String requestId,
                                    @Valid @RequestBody Commands.SubmitRequest cmd) {
        return service.updateRequest(requestId, cmd);
    }

    @PostMapping("/{requestId}/approve")
    public Views.RequestView approve(@PathVariable String requestId,
                                     @Valid @RequestBody Commands.Approve cmd) {
        return service.approve(requestId, cmd);
    }

    @PostMapping("/{requestId}/migrate")
    public Views.RequestView migrate(@PathVariable String requestId,
                                     @Valid @RequestBody Commands.Migrate cmd) {
        return service.migrate(requestId, cmd);
    }

    @PostMapping("/{requestId}/decommission")
    public Views.DecommissionView decommission(@PathVariable String requestId) {
        return service.decommission(requestId);
    }
}
