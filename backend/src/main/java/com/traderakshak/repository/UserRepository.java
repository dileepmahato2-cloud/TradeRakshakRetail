package com.traderakshak.repository;

import com.traderakshak.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// R9: All queries documented and parameterised (no string concatenation)
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByTrUserId(String trUserId);

    boolean existsByEmail(String email);

    List<User> findByInstitutionId(Long institutionId);

    List<User> findByPlanStatusAndPlanExpiryBefore(String planStatus, String date);

    @Query("SELECT COUNT(u) FROM User u")
    long countAll();

    @Query("SELECT COUNT(u) FROM User u WHERE u.institutionId = :iid")
    long countByInstitutionId(@Param("iid") Long institutionId);

    @Modifying
    @Query("UPDATE User u SET u.failedLoginCount = u.failedLoginCount + 1 WHERE u.id = :id")
    void incrementFailedLoginCount(@Param("id") Long id);

    @Modifying
    @Query("UPDATE User u SET u.failedLoginCount = 0, u.lockoutUntil = null WHERE u.id = :id")
    void resetFailedLogins(@Param("id") Long id);

    @Modifying
    @Query("UPDATE User u SET u.loginCount = u.loginCount + 1, u.lastLogin = :now, " +
           "u.consentAcceptedAt = :now, u.failedLoginCount = 0 WHERE u.id = :id")
    void recordSuccessfulLogin(@Param("id") Long id, @Param("now") String now);
}
