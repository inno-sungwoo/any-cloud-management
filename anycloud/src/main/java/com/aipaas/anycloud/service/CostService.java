package com.aipaas.anycloud.service;

import com.aipaas.anycloud.model.dto.request.GpuReservationRequestDto;

import java.util.List;

public interface CostService {

    Object summary(String clusterName);
    Object idleWarnings(String clusterName);
    Object report(String clusterName);
    Object estimate(int gpuCount, int hours);

    List<?> getReservations(String clusterName);
    Object createReservation(GpuReservationRequestDto dto);
    Object extendReservation(String releaseName, String clusterName, int additionalMinutes);
    void deleteReservation(String releaseName, String clusterName);
}
