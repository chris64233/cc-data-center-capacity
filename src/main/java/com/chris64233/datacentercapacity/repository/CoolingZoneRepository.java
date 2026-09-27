package com.chris64233.datacentercapacity.repository;

import com.chris64233.datacentercapacity.domain.CoolingZone;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CoolingZoneRepository extends JpaRepository<CoolingZone, Long> {

    Optional<CoolingZone> findByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select z from CoolingZone z where z.id = :id")
    Optional<CoolingZone> findByIdForUpdate(@Param("id") long id);
}
