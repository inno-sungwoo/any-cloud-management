package com.aipaas.anycloud.repository;

import com.aipaas.anycloud.model.entity.GpuReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GpuReservationRepository extends JpaRepository<GpuReservationEntity, Long> {

    List<GpuReservationEntity> findByClusterId(String clusterId);

    Optional<GpuReservationEntity> findByReleaseNameAndClusterId(String releaseName, String clusterId);

    void deleteByReleaseNameAndClusterId(String releaseName, String clusterId);
}
