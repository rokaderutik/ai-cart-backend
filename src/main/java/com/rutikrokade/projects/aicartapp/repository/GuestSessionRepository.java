package com.rutikrokade.projects.aicartapp.repository;

import java.util.Optional;

import com.rutikrokade.projects.aicartapp.entity.GuestSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuestSessionRepository extends JpaRepository<GuestSession, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GuestSession s where s.id = :id")
    Optional<GuestSession> findByIdForUpdate(@Param("id") String id);
}
