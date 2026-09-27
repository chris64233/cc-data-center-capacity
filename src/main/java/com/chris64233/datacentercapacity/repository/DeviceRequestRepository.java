package com.chris64233.datacentercapacity.repository;

import com.chris64233.datacentercapacity.domain.DeviceRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceRequestRepository extends JpaRepository<DeviceRequest, Long> {

    Optional<DeviceRequest> findByRequestNo(String requestNo);
}
