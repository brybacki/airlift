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

import com.google.common.collect.ImmutableMap;
import io.airlift.configuration.ConfigurationFactory;
import io.airlift.configuration.testing.ConfigAssertions;
import io.airlift.units.Duration;
import org.testng.annotations.Test;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static io.airlift.testing.ValidationAssertions.assertFailsValidation;

public class KubernetesDiscoveryConfigTest
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(KubernetesDiscoveryConfig.class)
                .setNamespace("default")
                .setLabelSelector(null) // Expected to be null by default, validated as @NotEmpty
                .setServicePortName(null)
                .setServicePortDefault(8080)
                .setRefreshInterval(new Duration(10, TimeUnit.SECONDS)));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("kubernetes.namespace", "my-namespace")
                .put("kubernetes.label-selector", "app=my-app,env=prod")
                .put("kubernetes.service-port-name", "http-api")
                .put("kubernetes.service-port-default", "9090")
                .put("kubernetes.refresh-interval", "30s")
                .build();

        KubernetesDiscoveryConfig expected = new KubernetesDiscoveryConfig()
                .setNamespace("my-namespace")
                .setLabelSelector("app=my-app,env=prod")
                .setServicePortName("http-api")
                .setServicePortDefault(9090)
                .setRefreshInterval(new Duration(30, TimeUnit.SECONDS));

        assertFullMapping(properties, expected);
    }

    @Test
    public void testValidation()
    {
        // Test valid config
        new ConfigurationFactory(ImmutableMap.of("kubernetes.label-selector", "app=test")).create(KubernetesDiscoveryConfig.class);

        // Test missing label selector
        assertFailsValidation(
                new ConfigurationFactory(ImmutableMap.of()).create(KubernetesDiscoveryConfig.class),
                "labelSelector",
                "must be provided to find services", // Adjusted to match the actual message if different
                NotEmpty.class // Or NotNull.class depending on the exact annotation used for the getter
        );


        // Test empty label selector
        assertFailsValidation(
                new ConfigurationFactory(ImmutableMap.of("kubernetes.label-selector", "")).create(KubernetesDiscoveryConfig.class),
                "labelSelector",
                "must be provided to find services",
                NotEmpty.class
        );

        // Test null refresh interval (if it were allowed to be null by config, but getter is @NotNull)
         assertFailsValidation(
             new ConfigurationFactory(ImmutableMap.of("kubernetes.label-selector", "app=test", "kubernetes.refresh-interval", "0s")).create(KubernetesDiscoveryConfig.class),
             "refreshInterval",
             "must be at least 1s", // MinDuration validation
             javax.validation.constraints.AssertTrue.class // This is how MinDuration is implemented
         );
    }
}
