package com.traderakshak.repository;
import com.traderakshak.entity.Strategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface StrategyRepository extends JpaRepository<Strategy,Long> {
    List<Strategy> findByStatusOrderByIdDesc(String status);
    List<Strategy> findByUserIdAndStatusOrderByIdDesc(Long userId, String status);
    List<Strategy> findByIsPublicAndStatusOrderByIdDesc(int isPublic, String status);
    long countByStatus(String status);
    long countByUserIdAndStatus(Long userId, String status);
}
