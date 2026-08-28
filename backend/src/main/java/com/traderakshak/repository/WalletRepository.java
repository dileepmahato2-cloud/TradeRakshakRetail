package com.traderakshak.repository;
import com.traderakshak.entity.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface WalletRepository extends JpaRepository<WalletTransaction,Long> {
    List<WalletTransaction> findByUserIdOrderByIdDesc(Long userId);
}
