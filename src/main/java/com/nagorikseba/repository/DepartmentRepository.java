package com.nagorikseba.repository;

import com.nagorikseba.entity.Department;
import com.nagorikseba.enums.ComplaintCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByWardIdAndName(Long wardId, ComplaintCategory name);
}
