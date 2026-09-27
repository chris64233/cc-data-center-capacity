package com.chris64233.datacentercapacity.repo;

import com.chris64233.datacentercapacity.domain.Rack;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RackRepository extends JpaRepository<Rack, Long> {

    Optional<Rack> findByCode(String code);

    /** 悲观写锁：容量预留/释放期间串行化对同一机柜的并发争抢。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Rack r where r.id = :id")
    Optional<Rack> lockById(Long id);
}
