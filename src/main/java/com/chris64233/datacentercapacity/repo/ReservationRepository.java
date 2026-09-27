package com.chris64233.datacentercapacity.repo;

import com.chris64233.datacentercapacity.domain.Reservation;
import com.chris64233.datacentercapacity.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByRackIdAndStatus(Long rackId, ReservationStatus status);

    List<Reservation> findByRequestIdAndStatusOrderByIdDesc(Long requestId, ReservationStatus status);

    List<Reservation> findByRequestIdOrderByIdDesc(Long requestId);

    /** 某制冷区域内当前生效预留的散热量合计。 */
    @Query("select coalesce(sum(r.heatLoadW), 0) from Reservation r "
            + "where r.rack.zone.id = :zoneId and r.status = :status")
    long sumHeatByZoneIdAndStatus(Long zoneId, ReservationStatus status);
}
