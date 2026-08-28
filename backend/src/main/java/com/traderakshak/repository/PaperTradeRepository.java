package com.traderakshak.repository;
import com.traderakshak.entity.PaperTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface PaperTradeRepository extends JpaRepository<PaperTrade,Long> {
    List<PaperTrade> findByStatusAndPortfolioNameOrderByIdDesc(String status, String portfolio);
    List<PaperTrade> findByUserIdAndStatusAndPortfolioNameOrderByIdDesc(Long userId, String status, String portfolio);
    List<PaperTrade> findByStatusAndPortfolioName(String status, String portfolio);
    long countByStatus(String status);
    @Query("SELECT COUNT(p) FROM PaperTrade p WHERE p.status='open' AND p.portfolioName=:pn")
    long countOpenByPortfolio(@Param("pn") String portfolioName);
    @Query("SELECT COUNT(p) FROM PaperTrade p WHERE p.userId=:uid AND p.status='open' AND p.portfolioName=:pn")
    long countOpenByUserAndPortfolio(@Param("uid") Long userId, @Param("pn") String portfolioName);
    @Query("SELECT COUNT(p) FROM PaperTrade p WHERE p.status='open' AND p.sector=:s AND p.portfolioName=:pn")
    long countOpenBySectorAndPortfolio(@Param("s") String sector, @Param("pn") String portfolioName);
    @Query("SELECT COUNT(p) FROM PaperTrade p WHERE p.userId=:uid AND p.status='open' AND p.sector=:s AND p.portfolioName=:pn")
    long countOpenByUserAndSectorAndPortfolio(@Param("uid") Long userId, @Param("s") String sector, @Param("pn") String portfolioName);
}
