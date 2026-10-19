package com.rutikrokade.projects.aicartapp.repository;

import com.rutikrokade.projects.aicartapp.entity.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {
}
