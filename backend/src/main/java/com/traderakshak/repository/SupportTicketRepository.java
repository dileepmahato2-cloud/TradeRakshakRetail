package com.traderakshak.repository;
import com.traderakshak.entity.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface SupportTicketRepository extends JpaRepository<SupportTicket,Long> {
    List<SupportTicket> findByUserIdOrderByIdDesc(Long userId);
    List<SupportTicket> findByStatusOrderByIdDesc(String status);
    List<SupportTicket> findByAssignedToOrderByIdDesc(Long assignedTo);
    long countByStatus(String status);
}
