package com.rutikrokade.projects.aicartapp.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.rutikrokade.projects.aicartapp.entity.CartItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    @EntityGraph(attributePaths = "menuItem")
    List<CartItem> findByCart_IdOrderByMenuItem_IdAsc(String cartId);

    Optional<CartItem> findByCart_IdAndMenuItem_Id(String cartId, Long menuItemId);
}
