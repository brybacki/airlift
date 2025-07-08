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
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.airlift.units.Duration;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;


public class KubernetesServiceSelectorTest
{
    private KubernetesClient mockClient;
    private MixedOperation<Pod, PodList, PodResource<Pod>> mockPodsOperation;
    private ServiceSelectorConfig selectorConfig;
    private KubernetesDiscoveryConfig k8sDiscoveryConfig;
    private KubernetesServiceSelector serviceSelector;

    @BeforeMethod
    public void setUp()
    {
        mockClient = mock(KubernetesClient.class);
        mockPodsOperation = mock(MixedOperation.class);

        when(mockClient.pods()).thenReturn(mockPodsOperation);
        when(mockPodsOperation.inNamespace(anyString())).thenReturn(mockPodsOperation);
        when(mockPodsOperation.withLabelSelector(anyString())).thenReturn(mockPodsOperation);

        selectorConfig = new ServiceSelectorConfig().setType("trino").setPool("test-pool");
        k8sDiscoveryConfig = new KubernetesDiscoveryConfig()
                .setNamespace("test-ns")
                .setLabelSelector("app=trino")
                .setServicePortDefault(8080)
                .setRefreshInterval(new Duration(1, TimeUnit.MILLISECONDS)); // Fast refresh for testing if needed, but we'll call refresh directly.
    }

    @AfterMethod
    public void tearDown() {
        if (serviceSelector != null) {
            serviceSelector.stop();
        }
    }

    private Pod createPod(String name, String ip, String phase, Map<String, String> labels, List<ContainerPort> ports)
    {
        Container container = new ContainerBuilder()
                .withName("trino-container")
                .withPorts(ports)
                .build();

        return new PodBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(k8sDiscoveryConfig.getNamespace())
                        .withLabels(labels)
                        .build())
                .withSpec(new io.fabric8.kubernetes.api.model.PodSpecBuilder()
                        .withContainers(container)
                        .withNodeName("test-node-" + name.hashCode()%5) // example node name
                        .build())
                .withStatus(new PodStatusBuilder()
                        .withPhase(phase)
                        .withPodIP(ip)
                        .withHostIP("10.0.0." + name.hashCode()%5) // example host IP
                        .build())
                .build();
    }

    @Test
    public void testSelectAllServices_HappyPath() throws ExecutionException, InterruptedException
    {
        ContainerPort port8080 = new ContainerPortBuilder().withName("http").withContainerPort(8080).build();
        Pod pod1 = createPod("pod1", "192.168.1.10", "Running", ImmutableMap.of("app", "trino", "role", "worker"), ImmutableList.of(port8080));
        Pod pod2 = createPod("pod2", "192.168.1.11", "Running", ImmutableMap.of("app", "trino", "role", "coordinator"), ImmutableList.of(port8080));
        PodList podList = new PodListBuilder().withItems(pod1, pod2).build();
        when(mockPodsOperation.list()).thenReturn(podList);

        serviceSelector = new KubernetesServiceSelector(selectorConfig, k8sDiscoveryConfig, mockClient);
        serviceSelector.start(); // Triggers initial refresh

        List<ServiceDescriptor> descriptors = serviceSelector.refresh().get(); // Force refresh and get result

        assertEquals(descriptors.size(), 2);

        ServiceDescriptor desc1 = descriptors.stream().filter(d -> d.getNodeId().equals("pod1")).findFirst().orElse(null);
        ServiceDescriptor desc2 = descriptors.stream().filter(d -> d.getNodeId().equals("pod2")).findFirst().orElse(null);

        assertDesc(desc1, "pod1", "trino", "test-pool", "192.168.1.10", 8080, ImmutableMap.of("app", "trino", "role", "worker"));
        assertDesc(desc2, "pod2", "trino", "test-pool", "192.168.1.11", 8080, ImmutableMap.of("app", "trino", "role", "coordinator"));
    }


    @Test
    public void testSelectAllServices_PortNameSpecified() throws ExecutionException, InterruptedException
    {
        k8sDiscoveryConfig.setServicePortName("api-http").setServicePortDefault(0); // Disable default to ensure named port is used

        ContainerPort namedPort = new ContainerPortBuilder().withName("api-http").withContainerPort(8081).build();
        ContainerPort otherPort = new ContainerPortBuilder().withName("metrics").withContainerPort(9090).build();
        Pod pod1 = createPod("pod-namedport", "192.168.1.20", "Running", ImmutableMap.of("app", "trino"), ImmutableList.of(namedPort, otherPort));
        PodList podList = new PodListBuilder().withItems(pod1).build();
        when(mockPodsOperation.list()).thenReturn(podList);

        serviceSelector = new KubernetesServiceSelector(selectorConfig, k8sDiscoveryConfig, mockClient);
        serviceSelector.start();

        List<ServiceDescriptor> descriptors = serviceSelector.refresh().get();
        assertEquals(descriptors.size(), 1);
        assertDesc(descriptors.get(0), "pod-namedport", "trino", "test-pool", "192.168.1.20", 8081, ImmutableMap.of("app", "trino"));
    }

    @Test
    public void testSelectAllServices_SkipsNonRunningPods() throws ExecutionException, InterruptedException
    {
        ContainerPort port8080 = new ContainerPortBuilder().withName("http").withContainerPort(8080).build();
        Pod runningPod = createPod("runningpod", "192.168.1.30", "Running", ImmutableMap.of("app", "trino"), ImmutableList.of(port8080));
        Pod pendingPod = createPod("pendingpod", "192.168.1.31", "Pending", ImmutableMap.of("app", "trino"), ImmutableList.of(port8080));
        PodList podList = new PodListBuilder().withItems(runningPod, pendingPod).build();
        when(mockPodsOperation.list()).thenReturn(podList);

        serviceSelector = new KubernetesServiceSelector(selectorConfig, k8sDiscoveryConfig, mockClient);
        serviceSelector.start();

        List<ServiceDescriptor> descriptors = serviceSelector.refresh().get();
        assertEquals(descriptors.size(), 1);
        assertEquals(descriptors.get(0).getNodeId(), "runningpod");
    }

    @Test
    public void testSelectAllServices_SkipsPodsWithNoIp() throws ExecutionException, InterruptedException
    {
        ContainerPort port8080 = new ContainerPortBuilder().withName("http").withContainerPort(8080).build();
        Pod podWithIp = createPod("podwithip", "192.168.1.40", "Running", ImmutableMap.of("app", "trino"), ImmutableList.of(port8080));
        Pod podNoIp = createPod("podnoip", null, "Running", ImmutableMap.of("app", "trino"), ImmutableList.of(port8080));
        PodList podList = new PodListBuilder().withItems(podWithIp, podNoIp).build();
        when(mockPodsOperation.list()).thenReturn(podList);

        serviceSelector = new KubernetesServiceSelector(selectorConfig, k8sDiscoveryConfig, mockClient);
        serviceSelector.start();

        List<ServiceDescriptor> descriptors = serviceSelector.refresh().get();
        assertEquals(descriptors.size(), 1);
        assertEquals(descriptors.get(0).getNodeId(), "podwithip");
    }


    @Test
    public void testSelectAllServices_SkipsPodsWithNoSuitablePort() throws ExecutionException, InterruptedException
    {
        k8sDiscoveryConfig.setServicePortName("non-existent-port").setServicePortDefault(0); // Ensure no fallback to default

        ContainerPort otherPort = new ContainerPortBuilder().withName("metrics").withContainerPort(9090).build();
        Pod podNoGoodPort = createPod("podnogoodport", "192.168.1.50", "Running", ImmutableMap.of("app", "trino"), ImmutableList.of(otherPort));
        PodList podList = new PodListBuilder().withItems(podNoGoodPort).build();
        when(mockPodsOperation.list()).thenReturn(podList);

        serviceSelector = new KubernetesServiceSelector(selectorConfig, k8sDiscoveryConfig, mockClient);
        serviceSelector.start();

        List<ServiceDescriptor> descriptors = serviceSelector.refresh().get();
        assertTrue(descriptors.isEmpty());
    }

    @Test
    public void testSelectAllServices_UsesDefaultPortWhenNameNotFound() throws ExecutionException, InterruptedException
    {
        k8sDiscoveryConfig.setServicePortName("non-existent-port").setServicePortDefault(7070);

        ContainerPort otherPort = new ContainerPortBuilder().withName("metrics").withContainerPort(9090).build();
        Pod pod1 = createPod("pod-defaultport", "192.168.1.60", "Running", ImmutableMap.of("app", "trino"), ImmutableList.of(otherPort));
        PodList podList = new PodListBuilder().withItems(pod1).build();
        when(mockPodsOperation.list()).thenReturn(podList);

        serviceSelector = new KubernetesServiceSelector(selectorConfig, k8sDiscoveryConfig, mockClient);
        serviceSelector.start();

        List<ServiceDescriptor> descriptors = serviceSelector.refresh().get();
        assertEquals(descriptors.size(), 1);
        assertDesc(descriptors.get(0), "pod-defaultport", "trino", "test-pool", "192.168.1.60", 7070, ImmutableMap.of("app", "trino"));
    }

    @Test
    public void testSelectAllServices_HandlesKubernetesClientException()
    {
        when(mockPodsOperation.list()).thenThrow(new io.fabric8.kubernetes.client.KubernetesClientException("K8S API error"));

        serviceSelector = new KubernetesServiceSelector(selectorConfig, k8sDiscoveryConfig, mockClient);
        serviceSelector.start(); // Initial refresh might log an error

        try {
            serviceSelector.refresh().get(); // Force refresh
            fail("Expected ExecutionException");
        } catch (InterruptedException e) {
            fail("Unexpected InterruptedException", e);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof io.fabric8.kubernetes.client.KubernetesClientException);
            assertEquals(e.getCause().getMessage(), "K8S API error");
        }
        // After an error, the list of service descriptors should ideally be empty or the last known good list.
        // Current implementation clears on error, so it should be empty.
        assertTrue(serviceSelector.selectAllServices().isEmpty(), "Service descriptors should be empty after a K8s client exception during refresh.");
    }


    private void assertDesc(ServiceDescriptor desc, String expectedNodeId, String expectedType, String expectedPool, String expectedIp, int expectedPort, Map<String, String> expectedLabels)
    {
        if (desc == null) {
            fail("ServiceDescriptor for " + expectedNodeId + " is null");
        }
        assertEquals(desc.getNodeId(), expectedNodeId);
        assertEquals(desc.getType(), expectedType);
        assertEquals(desc.getPool(), expectedPool);
        assertEquals(desc.getState(), ServiceState.RUNNING);

        String expectedScheme = (expectedPort == 443 || expectedPort == 8443) ? "https" : "http";
        String expectedUri = expectedScheme + "://" + expectedIp + ":" + expectedPort;
        assertEquals(desc.getProperties().get(expectedScheme), expectedUri, "Checking " + expectedScheme + " URI");

        for (Map.Entry<String, String> labelEntry : expectedLabels.entrySet()) {
            assertEquals(desc.getProperties().get(labelEntry.getKey()), labelEntry.getValue(), "Checking label " + labelEntry.getKey());
        }
        assertTrue(desc.getProperties().containsKey("nodeName"));
        assertTrue(desc.getProperties().containsKey("hostIP"));
    }
}
