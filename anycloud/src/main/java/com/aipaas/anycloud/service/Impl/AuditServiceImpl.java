package com.aipaas.anycloud.service.Impl;

import com.aipaas.anycloud.configuration.bean.KubeconfigProvider;
import com.aipaas.anycloud.configuration.bean.KubernetesClientConfig;
import com.aipaas.anycloud.error.exception.EntityNotFoundException;
import com.aipaas.anycloud.model.dto.response.AuditEventDto;
import com.aipaas.anycloud.model.entity.ClusterEntity;
import com.aipaas.anycloud.repository.ClusterRepository;
import com.aipaas.anycloud.service.AuditService;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventList;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

	private final ClusterRepository clusterRepository;
	private final KubeconfigProvider kubeconfigProvider;

	@Override
	public List<AuditEventDto> getEvents(String clusterName, String namespace) {
		ClusterEntity cluster = clusterRepository.findById(clusterName).orElseThrow(
				() -> new EntityNotFoundException("Cluster with Name " + clusterName + " Not Found."));

		KubernetesClientConfig manager = null;
		try {
			manager = new KubernetesClientConfig(cluster, kubeconfigProvider.resolvePath());
			KubernetesClient client = manager.getClient();

			EventList eventList = (namespace == null || namespace.isBlank())
					? client.v1().events().inAnyNamespace().list()
					: client.v1().events().inNamespace(namespace).list();
			List<AuditEventDto> events = new ArrayList<>();

			for (Event event : eventList.getItems()) {
				String involvedObj = "";
				if (event.getInvolvedObject() != null) {
					involvedObj = event.getInvolvedObject().getKind() + "/" + event.getInvolvedObject().getName();
				}

				events.add(AuditEventDto.builder()
						.reason(event.getReason())
						.message(event.getMessage())
						.involvedObject(involvedObj)
						.namespace(event.getMetadata().getNamespace())
						.type(event.getType())
						.firstTimestamp(event.getFirstTimestamp())
						.lastTimestamp(event.getLastTimestamp())
						.build());
			}

			return events;
		} finally {
			if (manager != null) {
				manager.closeClient();
			}
		}
	}
}
