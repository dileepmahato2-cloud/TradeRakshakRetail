package com.traderakshak.repository;
import com.traderakshak.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
@Repository
public interface CouponRepository extends JpaRepository<Coupon,Long> {
    Optional<Coupon> findByCodeAndStatus(String code, String status);
    boolean existsByCode(String code);
}
