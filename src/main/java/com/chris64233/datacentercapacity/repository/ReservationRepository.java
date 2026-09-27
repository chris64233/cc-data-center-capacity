package com.chris64233.datacentercapacity.repository;

import com.chris64233.datacentercapacity.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByRequestRequestNoOrderByIdAsc(String requestNo);

    /** 机柜上仍占用资源的预留（已预留或已交付） */
    @Query("select r from Reservation r where r.rack.id = :rackId and r.status <> 'RELEASED'")
    List<Reservation> findActiveByRackId(@Param("rackId") long rackId);
}
