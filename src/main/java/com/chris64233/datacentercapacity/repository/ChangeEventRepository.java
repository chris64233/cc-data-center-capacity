package com.chris64233.datacentercapacity.repository;

import com.chris64233.datacentercapacity.domain.ChangeEvent;
import com.chris64233.datacentercapacity.domain.EventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChangeEventRepository extends JpaRepository<ChangeEvent, Long> {

    List<ChangeEvent> findAllByOrderByIdAsc();

    List<ChangeEvent> findByRequestNoOrderByIdAsc(String requestNo);

    List<ChangeEvent> findByTypeOrderByIdAsc(EventType type);

    List<ChangeEvent> findByRequestNoAndTypeOrderByIdAsc(String requestNo, EventType type);
}
