/*
 * Copyright (C) 2023 an undisclosed author*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.discovery.client;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static java.util.Objects.requireNonNull;

public class KubernetesServiceSelector
        implements ServiceSelector
{
    private static final Logger log = Logger.get(KubernetesServiceSelector.class);

    private final String type; // Service type (e.g., "trino")
    private final String pool; // Service pool (e.g., "general")
    private final KubernetesDiscoveryConfig config;
    private final KubernetesClient kubernetesClient;
    private final ScheduledExecutorService executorService;

    private final AtomicReference<List<ServiceDescriptor>> serviceDescriptors = new AtomicReference<>(ImmutableList.of());
    private volatile ScheduledFuture<?> refreshJob;

    @Inject
    public KubernetesServiceSelector(ServiceSelectorConfig selectorConfig, KubernetesDiscoveryConfig kubernetesConfig, KubernetesClient kubernetesClient)
    {
        requireNonNull(selectorConfig, "selectorConfig is null");
        this.type = selectorConfig.getType();
        this.pool = selectorConfig.getPool();

        this.config = requireNonNull(kubernetesConfig, "kubernetesConfig is null");
        this.kubernetesClient = requireNonNull(kubernetesClient, "kubernetesClient is null");
        // TODO: Consider using a shared executor for all K8s selectors if many are created
        this.executorService = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(daemonThreadsNamed("kubernetes-service-selector-" + type + "-" + pool + "-%s"));
    }

    @PostConstruct
    public void start()
    {
        // Refresh immediately and then schedule periodic refreshes
        try {
            refresh();
        }
        catch (Exception e) {
            log.warn(e, "Initial refresh failed for service type %s, pool %s. Subsequent refreshes will be attempted.", type, pool);
        }

        Duration refreshInterval = config.getRefreshInterval();
        if (refreshInterval != null) {
            refreshJob = executorService.scheduleWithFixedDelay(
                    this::refreshSafe,
                    refreshInterval.toMillis(),
                    refreshInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    public void stop()
    {
        if (refreshJob != null) {
            refreshJob.cancel(true);
            refreshJob = null;
        }
        executorService.shutdownNow();
        try {
            executorService.awaitTermination(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void refreshSafe() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn(e, "Error refreshing services for type %s, pool %s from Kubernetes", type, pool);
        }
    }


    @Override
    public String getType()
    {
        return type;
    }

    @Override
    public String getPool()
    {
        return pool;
    }

    @Override
    public List<ServiceDescriptor> selectAllServices()
    {
        return serviceDescriptors.get();
    }

    @Override
    public ListenableFuture<List<ServiceDescriptor>> refresh()
    {
        SettableFuture<List<ServiceDescriptor>> future = SettableFuture.create();
        try {
            List<Pod> pods;
            try {
                pods = kubernetesClient.pods()
                        .inNamespace(config.getNamespace())
                        .withLabelSelector(config.getLabelSelector())
                        .list()
                        .getItems();
            }
            catch (KubernetesClientException e) {
                log.warn(e, "Failed to fetch pods from Kubernetes API for namespace %s, selector %s", config.getNamespace(), config.getLabelSelector());
                future.setException(e);
                return future;
            }

            ImmutableList.Builder<ServiceDescriptor> descriptors = ImmutableList.builder();
            for (Pod pod : pods) {
                if (!"Running".equalsIgnoreCase(pod.getStatus().getPhase())) {
                    log.debug("Skipping pod %s in namespace %s as it is not in Running phase (current phase: %s)", pod.getMetadata().getName(), config.getNamespace(), pod.getStatus().getPhase());
                    continue;
                }
                if (pod.getStatus().getPodIP() == null || pod.getStatus().getPodIP().isEmpty()) {
                    log.debug("Skipping pod %s in namespace %s as it does not have an IP address", pod.getMetadata().getName(), config.getNamespace());
                    continue;
                }

                String podIp = pod.getStatus().getPodIP();
                OptionalInt port = findServicePort(pod);

                if (!port.isPresent()) {
                    log.warn("Pod %s in namespace %s matches selector but no suitable port found (configured port name: %s, default port: %d). Skipping.",
                            pod.getMetadata().getName(), config.getNamespace(), config.getServicePortName(), config.getServicePortDefault());
                    continue;
                }

                ImmutableMap.Builder<String, String> properties = ImmutableMap.builder();
                // Standard http/https properties
                // Assuming service type implies protocol or we add config for it
                // For now, let's assume http, and https if port is 443 or a common https port for the type
                // This logic might need refinement based on Trino's actual usage.
                String scheme = (port.getAsInt() == 443 || port.getAsInt() == 8443) ? "https" : "http";
                properties.put(scheme, scheme + "://" + podIp + ":" + port.getAsInt());
                // Add all pod labels as properties
                if (pod.getMetadata().getLabels() != null) {
                    properties.putAll(pod.getMetadata().getLabels());
                }

                // Include node name and host IP if available
                if (pod.getSpec() != null && pod.getSpec().getNodeName() != null) {
                    properties.put("nodeName", pod.getSpec().getNodeName());
                }
                if (pod.getStatus().getHostIP() != null) {
                    properties.put("hostIP", pod.getStatus().getHostIP());
                }


                ServiceDescriptor descriptor = ServiceDescriptor.builder(type)
                        .setNodeId(pod.getMetadata().getName()) // Using pod name as Node ID, could be configurable
                        .setPool(pool)
                        .setLocation("/" + config.getNamespace() + "/" + pod.getMetadata().getName()) // Arbitrary location based on k8s info
                        .setState(ServiceState.RUNNING) // Assuming running if pod is Running
                        .addProperties(properties.build())
                        .build();
                descriptors.add(descriptor);
            }
            List<ServiceDescriptor> newDescriptors = descriptors.build();
            this.serviceDescriptors.set(newDescriptors);
            future.set(newDescriptors);
            log.debug("Refreshed services for type %s, pool %s: %s", type, pool, newDescriptors.stream().map(ServiceDescriptor::getNodeId).collect(Collectors.joining(", ")));

        }
        catch (Exception e) {
            log.error(e, "Error refreshing service descriptors from Kubernetes for type %s, pool %s", type, pool);
            future.setException(e);
        }
        return future;
    }

    private OptionalInt findServicePort(Pod pod)
    {
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null || pod.getSpec().getContainers().isEmpty()) {
            return OptionalInt.empty();
        }

        // Try to find by configured port name first
        if (config.getServicePortName() != null && !config.getServicePortName().isEmpty()) {
            for (io.fabric8.kubernetes.api.model.Container container : pod.getSpec().getContainers()) {
                if (container.getPorts() != null) {
                    for (ContainerPort cPort : container.getPorts()) {
                        if (Objects.equals(cPort.getName(), config.getServicePortName()) && cPort.getContainerPort() != null) {
                            return OptionalInt.of(cPort.getContainerPort());
                        }
                    }
                }
            }
        }

        // If not found by name, or name not configured, try common Trino/HTTP ports or default
        // This logic can be expanded. For now, prioritize the default.
        if (config.getServicePortDefault() > 0) {
             // Check if the default port actually exists on any container, if not, it's a blind default
            for (io.fabric8.kubernetes.api.model.Container container : pod.getSpec().getContainers()) {
                if (container.getPorts() != null) {
                    for (ContainerPort cPort : container.getPorts()) {
                        if (cPort.getContainerPort() != null && cPort.getContainerPort() == config.getServicePortDefault()) {
                            return OptionalInt.of(config.getServicePortDefault());
                        }
                    }
                }
            }
            // If the default port is not explicitly listed, but a default is configured, use it.
            // This allows services that don't define ports in their spec but listen on a known port.
            log.debug("Pod %s in namespace %s: using default port %d as specified port name '%s' not found or not configured, and default port not explicitly listed in container ports.",
                    pod.getMetadata().getName(), config.getNamespace(), config.getServicePortDefault(), config.getServicePortName());
            return OptionalInt.of(config.getServicePortDefault());
        }


        // Fallback: if no port name and no default, try to find any port named "http" or "https"
        for (io.fabric8.kubernetes.api.model.Container container : pod.getSpec().getContainers()) {
            if (container.getPorts() != null) {
                for (ContainerPort cPort : container.getPorts()) {
                    if (("http".equalsIgnoreCase(cPort.getName()) || "https".equalsIgnoreCase(cPort.getName())) && cPort.getContainerPort() != null) {
                        return OptionalInt.of(cPort.getContainerPort());
                    }
                }
            }
        }

        // Final fallback: if only one port is exposed on the first container, use it.
        if (pod.getSpec().getContainers().get(0).getPorts() != null &&
            pod.getSpec().getContainers().get(0).getPorts().size() == 1 &&
            pod.getSpec().getContainers().get(0).getPorts().get(0).getContainerPort() != null ) {
            log.debug("Pod %s in namespace %s: falling back to the single port %d on the first container.",
                    pod.getMetadata().getName(), config.getNamespace(), pod.getSpec().getContainers().get(0).getPorts().get(0).getContainerPort());
            return OptionalInt.of(pod.getSpec().getContainers().get(0).getPorts().get(0).getContainerPort());
        }


        return OptionalInt.empty();
    }
}
