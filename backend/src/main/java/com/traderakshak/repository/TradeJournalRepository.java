package com.traderakshak.repository;
import com.traderakshak.entity.TradeJournal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface TradeJournalRepository extends JpaRepository<TradeJournal,Long> {
    List<TradeJournal> findByUserIdOrderByIdDesc(Long userId);
    List<TradeJournal> findAllByOrderByIdDesc();
    @Query("SELECT COALESCE(SUM(t.netPnl),0) FROM TradeJournal t WHERE t.userId=:uid")
    Double sumNetPnlByUser(@Param("uid") Long userId);
    long countByUserId(Long userId);
    long count();
}
