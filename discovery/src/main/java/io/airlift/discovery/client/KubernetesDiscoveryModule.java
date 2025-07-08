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

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.airlift.log.Logger;


import javax.inject.Singleton;

import static io.airlift.configuration.ConfigBinder.configBinder;
import static com.google.inject.multibindings.Multibinder.newSetBinder;

public class KubernetesDiscoveryModule
        implements Module
{
    private static final Logger log = Logger.get(KubernetesDiscoveryModule.class);

    @Override
    public void configure(Binder binder)
    {
        // Bind configuration
        configBinder(binder).bindConfig(KubernetesDiscoveryConfig.class);
        // ServiceSelectorConfig is needed by KubernetesServiceSelector for type and pool
        configBinder(binder).bindConfig(ServiceSelectorConfig.class);


        // Bind KubernetesServiceSelector as the ServiceSelector
        // This uses a multibinder to allow for multiple ServiceSelector implementations,
        // though for this specific use case, we might be replacing others.
        // If this is the *only* selector, specific binding might be considered.
        newSetBinder(binder, ServiceSelector.class).addBinding().to(KubernetesServiceSelector.class).in(Scopes.SINGLETON);

        // Ensure ServiceSelectorManager is available if it's needed to manage these selectors
        // This is typically part of the core DiscoveryModule, but if we're replacing it,
        // we might need to ensure its core functionalities are present or re-bound.
        // For now, assume an existing ServiceSelectorManager or that it's not strictly needed
        // if only one selector type is being used and directly injected.
        // However, to be safe and compatible with existing patterns:
        binder.bind(ServiceSelectorManager.class).in(Scopes.SINGLETON);

        // Unlike DiscoveryModule, we do NOT bind Announcer or DiscoveryAnnouncementClient,
        // as this module relies on K8s for discovery, not self-announcement.
    }

    @Provides
    @Singleton
    public KubernetesClient createKubernetesClient(KubernetesDiscoveryConfig k8sConfig)
    {
        try {
            // This will use in-cluster config if running in Kubernetes,
            // or kubeconfig from ~/.kube/config otherwise.
            Config config = new ConfigBuilder().withNamespace(k8sConfig.getNamespace()).build();
            return new DefaultKubernetesClient(config);
        }
        catch (KubernetesClientException e) {
            log.error(e, "Failed to create Kubernetes client");
            // Depending on application requirements, you might throw a Guice ConfigurationException
            // or allow the application to start without discovery working.
            // For now, rethrow to prevent startup if K8s client can't be initialized.
            throw new RuntimeException("Failed to initialize Kubernetes client", e);
        }
    }
}
