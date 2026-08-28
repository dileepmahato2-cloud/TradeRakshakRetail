package com.traderakshak.repository;
import com.traderakshak.entity.ActivityLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog,Long> {
    List<ActivityLog> findTop50ByOrderByIdDesc();
    Page<ActivityLog> findAllByOrderByIdDesc(Pageable pageable);
    long count();
}
