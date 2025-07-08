/*
 * Copyright 2010 Proofpoint, Inc.
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
package io.airlift.sample;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Scopes;

import com.google.inject.Scopes;
import io.airlift.discovery.client.KubernetesDiscoveryModule;
import io.airlift.discovery.client.ServiceSelectorConfig;

import static io.airlift.configuration.ConfigBinder.configBinder;
// import static io.airlift.discovery.client.DiscoveryBinder.discoveryBinder; // Replaced by KubernetesDiscoveryModule
import static io.airlift.event.client.EventBinder.eventBinder;
import static io.airlift.jaxrs.JaxrsBinder.jaxrsBinder;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class MainModule
        implements Module
{
    @Override
    public void configure(Binder binder)
    {
        binder.disableCircularProxies();

        binder.bind(PersonStore.class).in(Scopes.SINGLETON);
        newExporter(binder).export(PersonStore.class).withGeneratedName();

        jaxrsBinder(binder).bind(PersonsResource.class);
        jaxrsBinder(binder).bind(PersonResource.class);

        configBinder(binder).bindConfig(StoreConfig.class);
        eventBinder(binder).bindEventClient(PersonEvent.class);

        // Configure the service type for KubernetesServiceSelector
        // This would typically come from a config file, but for simplicity in MainModule:
        configBinder(binder).bindConfigDefaults(ServiceSelectorConfig.class, config -> {
            config.setType("person"); // The service type this server is interested in discovering
            // config.setPool("general"); // Optionally set pool if needed, defaults to 'general'
        });
        binder.install(new KubernetesDiscoveryModule());
        // Note: We no longer call discoveryBinder(binder).bindHttpAnnouncement("person");
        // because service announcement is not part of the Kubernetes discovery model.
        // The server itself is assumed to be discoverable via Kubernetes labels.
    }
}
