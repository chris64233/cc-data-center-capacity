package com.chris64233.datacentercapacity.web;

import com.chris64233.datacentercapacity.service.CapacityService;
import com.chris64233.datacentercapacity.web.Dtos.ApproveDto;
import com.chris64233.datacentercapacity.web.Dtos.RequestView;
import com.chris64233.datacentercapacity.web.Dtos.ReservationView;
import com.chris64233.datacentercapacity.web.Dtos.SubmitRequestDto;
import com.chris64233.datacentercapacity.web.Dtos.UpdateRequestDto;
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
@RequestMapping("/api/requests")
public class RequestController {

    private final CapacityService service;

    public RequestController(CapacityService service) {
        this.service = service;
    }

    /** 提交申请，requestNo 幂等。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView submit(@Valid @RequestBody SubmitRequestDto dto) {
        return service.submitRequest(dto);
    }

    @GetMapping("/{requestNo}")
    public RequestView get(@PathVariable String requestNo) {
        return service.getRequest(requestNo);
    }

    @PutMapping("/{requestNo}")
    public RequestView update(@PathVariable String requestNo, @Valid @RequestBody UpdateRequestDto dto) {
        return service.updateRequest(requestNo, dto);
    }

    /** 批准申请并一次性预留机位、双路电力和制冷。 */
    @PostMapping("/{requestNo}/approve")
    public ReservationView approve(@PathVariable String requestNo, @Valid @RequestBody ApproveDto dto) {
        return service.approve(requestNo, dto);
    }
}
