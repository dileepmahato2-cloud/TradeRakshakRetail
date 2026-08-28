package com.traderakshak.repository;
import com.traderakshak.entity.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
@Repository
public interface MarketDataRepository extends JpaRepository<MarketData,Long> {
    @Query("SELECT MAX(m.tradeDate) FROM MarketData m WHERE m.tradeDate <= :today")
    Optional<String> findLastMarketDate(@Param("today") String today);
    List<MarketData> findByTradeDate(String tradeDate);
    @Query("SELECT COUNT(DISTINCT m.symbol) FROM MarketData m")
    long countDistinctSymbols();
    @Query("SELECT m FROM MarketData m WHERE m.exchange=:ex AND m.symbol=:sym ORDER BY m.tradeDate DESC")
    List<MarketData> findLatestBySymbol(@Param("ex") String exchange, @Param("sym") String symbol);
    long countByTradeDateGreaterThanEqual(String date);
}
