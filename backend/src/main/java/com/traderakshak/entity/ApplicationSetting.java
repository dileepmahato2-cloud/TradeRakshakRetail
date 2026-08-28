package com.traderakshak.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "application_settings")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApplicationSetting {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String category;

    @Column(name = "setting_key", unique = true)
    private String settingKey;

    @Column(name = "setting_name")
    private String settingName;

    @Column(name = "setting_value")
    private String settingValue;

    @Column(name = "data_type")
    @Builder.Default
    private String dataType = "STRING";

    @Column(name = "default_value")
    private String defaultValue;

    @Column(name = "min_value")
    private String minValue;

    @Column(name = "max_value")
    private String maxValue;

    private String description;

    @Column(name = "is_editable")
    @Builder.Default
    private Integer isEditable = 1;

    @Column(name = "is_active")
    @Builder.Default
    private Integer isActive = 1;

    @Column(name = "display_order")
    @Builder.Default
    private Integer displayOrder = 0;

    @Column(name = "config_version")
    @Builder.Default
    private Integer configVersion = 1;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at")
    private String createdAt;

    @Column(name = "updated_at")
    private String updatedAt;
}
