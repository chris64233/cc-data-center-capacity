package com.chris64233.datacentercapacity.repository;

import com.chris64233.datacentercapacity.domain.Rack;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RackRepository extends JpaRepository<Rack, Long> {

    Optional<Rack> findByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Rack r where r.id = :id")
    Optional<Rack> findByIdForUpdate(@Param("id") long id);
}
