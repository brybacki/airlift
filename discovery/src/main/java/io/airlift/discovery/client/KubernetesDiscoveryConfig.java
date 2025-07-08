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

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.units.MinDuration;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.concurrent.TimeUnit;
import io.airlift.units.Duration;

public class KubernetesDiscoveryConfig
{
    private String namespace = "default";
    private String labelSelector;
    private String servicePortName;
    private int servicePortDefault = 8080;
    private Duration refreshInterval = new Duration(10, TimeUnit.SECONDS);

    @NotNull
    public String getNamespace()
    {
        return namespace;
    }

    @Config("kubernetes.namespace")
    @ConfigDescription("Kubernetes namespace to search for services")
    public KubernetesDiscoveryConfig setNamespace(String namespace)
    {
        this.namespace = namespace;
        return this;
    }

    @NotEmpty(message = "kubernetes.label-selector must be provided to find services")
    public String getLabelSelector()
    {
        return labelSelector;
    }

    @Config("kubernetes.label-selector")
    @ConfigDescription("Kubernetes label selector to find service pods (e.g., app=trino,role=coordinator)")
    public KubernetesDiscoveryConfig setLabelSelector(String labelSelector)
    {
        this.labelSelector = labelSelector;
        return this;
    }

    public String getServicePortName()
    {
        return servicePortName;
    }

    @Config("kubernetes.service-port-name")
    @ConfigDescription("Name of the port in pod spec to use for HTTP/HTTPS URIs (optional)")
    public KubernetesDiscoveryConfig setServicePortName(String servicePortName)
    {
        this.servicePortName = servicePortName;
        return this;
    }

    public int getServicePortDefault()
    {
        return servicePortDefault;
    }

    @Config("kubernetes.service-port-default")
    @ConfigDescription("Default port to use for HTTP/HTTPS URIs if service-port-name is not specified or not found")
    public KubernetesDiscoveryConfig setServicePortDefault(int servicePortDefault)
    {
        this.servicePortDefault = servicePortDefault;
        return this;
    }

    @NotNull
    @MinDuration("1s")
    public Duration getRefreshInterval()
    {
        return refreshInterval;
    }

    @Config("kubernetes.refresh-interval")
    @ConfigDescription("Refresh interval for polling Kubernetes API")
    public KubernetesDiscoveryConfig setRefreshInterval(Duration refreshInterval)
    {
        this.refreshInterval = refreshInterval;
        return this;
    }
}
