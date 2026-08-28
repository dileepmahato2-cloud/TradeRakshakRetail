package com.traderakshak.repository;

import com.traderakshak.entity.ApplicationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ApplicationSettingRepository extends JpaRepository<ApplicationSetting, Long> {
    Optional<ApplicationSetting> findBySettingKey(String settingKey);
    List<ApplicationSetting> findByCategoryOrderByDisplayOrder(String category);
    List<ApplicationSetting> findByIsActiveOrderByCategoryAscDisplayOrderAsc(Integer isActive);
    List<ApplicationSetting> findByCategoryAndIsActiveOrderByDisplayOrder(String category, Integer isActive);
    boolean existsBySettingKey(String settingKey);
}
