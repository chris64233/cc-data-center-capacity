package com.chris64233.datacentercapacity.repo;

import com.chris64233.datacentercapacity.domain.CoolingZone;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CoolingZoneRepository extends JpaRepository<CoolingZone, Long> {

    Optional<CoolingZone> findByCode(String code);

    /** 悲观写锁：容量预留/释放期间串行化对同一区域的并发变更。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select z from CoolingZone z where z.id = :id")
    Optional<CoolingZone> lockById(Long id);
}
