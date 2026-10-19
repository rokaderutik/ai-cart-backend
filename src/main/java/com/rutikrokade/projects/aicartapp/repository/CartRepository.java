package com.rutikrokade.projects.aicartapp.repository;

import java.util.Optional;

import com.rutikrokade.projects.aicartapp.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartRepository extends JpaRepository<Cart, String> {

    Optional<Cart> findBySession_Id(String sessionId);
}
