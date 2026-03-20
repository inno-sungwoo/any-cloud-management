package com.aipaas.anycloud.service;

public interface CostService {

    Object summary(String clusterName);
    Object idleWarnings(String clusterName);
    Object report(String clusterName);
    Object estimate(int gpuCount, int hours);
}
