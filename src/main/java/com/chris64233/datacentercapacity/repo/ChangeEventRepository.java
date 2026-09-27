package com.chris64233.datacentercapacity.repo;

import com.chris64233.datacentercapacity.domain.ChangeEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChangeEventRepository extends JpaRepository<ChangeEvent, Long> {

    List<ChangeEvent> findAllByOrderByIdAsc();
}
