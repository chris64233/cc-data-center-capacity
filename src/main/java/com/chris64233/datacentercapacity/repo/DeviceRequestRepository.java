package com.chris64233.datacentercapacity.repo;

import com.chris64233.datacentercapacity.domain.DeviceRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface DeviceRequestRepository extends JpaRepository<DeviceRequest, Long> {

    Optional<DeviceRequest> findByRequestId(String requestId);

    /** 悲观写锁：同一申请的批准/迁移/下架操作互斥执行。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeviceRequest d where d.requestId = :requestId")
    Optional<DeviceRequest> lockByRequestId(String requestId);
}
