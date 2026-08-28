package com.traderakshak.repository;
import com.traderakshak.entity.SectorData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface SectorDataRepository extends JpaRepository<SectorData, Long> {
    List<SectorData> findByTradeDate(String tradeDate);
}
