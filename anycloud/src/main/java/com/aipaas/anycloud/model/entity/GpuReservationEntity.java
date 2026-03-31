package com.aipaas.anycloud.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "gpu_reservation", schema = "aipaas",
        uniqueConstraints = @UniqueConstraint(columnNames = {"release_name", "cluster_id"}))
public class GpuReservationEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_name", nullable = false, length = 100)
    private String releaseName;

    @Column(name = "namespace", nullable = false, length = 100)
    private String namespace;

    @Column(name = "cluster_id", nullable = false, length = 45)
    private String clusterId;

    @Column(name = "gpu_count", nullable = false)
    private Integer gpuCount;

    @Column(name = "estimated_minutes", nullable = false)
    private Integer estimatedMinutes;

    @Column(name = "unit_price_krw", nullable = false)
    private Integer unitPriceKrw;

    @Column(name = "estimated_cost_krw", nullable = false)
    private Integer estimatedCostKrw;

    @Column(name = "deployed_at", nullable = false)
    private LocalDateTime deployedAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
