package com.aipaas.anycloud.service;

import com.aipaas.anycloud.model.dto.response.AuditEventDto;

import java.util.List;

public interface AuditService {

	List<AuditEventDto> getEvents(String clusterName, String namespace);
}
