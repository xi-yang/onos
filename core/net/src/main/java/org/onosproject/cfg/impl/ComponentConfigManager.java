/*
 * Copyright 2015 Open Networking Laboratory
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
package org.onosproject.cfg.impl;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.cfg.ComponentConfigEvent;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.cfg.ComponentConfigStore;
import org.onosproject.cfg.ComponentConfigStoreDelegate;
import org.onosproject.cfg.ConfigProperty;
import org.onosproject.core.Permission;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.slf4j.LoggerFactory.getLogger;
import static org.onosproject.security.AppGuard.checkPermission;


/**
 * Implementation of the centralized component configuration service.
 */
@Component(immediate = true)
@Service
public class ComponentConfigManager implements ComponentConfigService {

    private static final String COMPONENT_NULL = "Component name cannot be null";
    private static final String PROPERTY_NULL = "Property name cannot be null";

    private static final String RESOURCE_EXT = ".cfgdef";

    private final Logger log = getLogger(getClass());

    private final ComponentConfigStoreDelegate delegate = new InternalStoreDelegate();

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigStore store;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ConfigurationAdmin cfgAdmin;

    // Locally maintained catalog of definitions.
    private final Map<String, Map<String, ConfigProperty>> properties =
            Maps.newConcurrentMap();

    @Activate
    public void activate() {
        store.setDelegate(delegate);
        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        store.unsetDelegate(delegate);
        log.info("Stopped");
    }

    @Override
    public Set<String> getComponentNames() {
        checkPermission(Permission.CONFIG_READ);

        return ImmutableSet.copyOf(properties.keySet());
    }

    @Override
    public void registerProperties(Class<?> componentClass) {
        checkPermission(Permission.CONFIG_WRITE);

        String componentName = componentClass.getName();
        String resourceName = componentClass.getSimpleName() + RESOURCE_EXT;
        try (InputStream ris = componentClass.getResourceAsStream(resourceName)) {
            checkArgument(ris != null, "Property definitions not found at resource %s",
                          resourceName);

            // Read the definitions
            Set<ConfigProperty> defs = ConfigPropertyDefinitions.read(ris);

            // Produce a new map of the properties and register it.
            Map<String, ConfigProperty> map = Maps.newConcurrentMap();
            defs.forEach(p -> map.put(p.name(), p));

            properties.put(componentName, map);
            loadExistingValues(componentName);
        } catch (IOException e) {
            log.error("Unable to read property definitions from resource " + resourceName, e);
        }
    }

    @Override
    public void unregisterProperties(Class<?> componentClass, boolean clear) {
        checkPermission(Permission.CONFIG_WRITE);

        String componentName = componentClass.getName();
        checkNotNull(componentName, COMPONENT_NULL);
        Map<String, ConfigProperty> cps = properties.remove(componentName);
        if (clear && cps != null) {
            cps.keySet().forEach(name -> store.unsetProperty(componentName, name));
            clearExistingValues(componentName);
        }
    }

    // Clears any existing values that may have been set.
    private void clearExistingValues(String componentName) {
        triggerUpdate(componentName);
    }

    @Override
    public Set<ConfigProperty> getProperties(String componentName) {
        checkPermission(Permission.CONFIG_READ);

        Map<String, ConfigProperty> map = properties.get(componentName);
        return map != null ? ImmutableSet.copyOf(map.values()) : null;
    }

    @Override
    public void setProperty(String componentName, String name, String value) {
        checkPermission(Permission.CONFIG_WRITE);

        checkNotNull(componentName, COMPONENT_NULL);
        checkNotNull(name, PROPERTY_NULL);
        store.setProperty(componentName, name, value);
    }

    @Override
    public void unsetProperty(String componentName, String name) {
        checkPermission(Permission.CONFIG_WRITE);

        checkNotNull(componentName, COMPONENT_NULL);
        checkNotNull(name, PROPERTY_NULL);
        store.unsetProperty(componentName, name);
    }

    private class InternalStoreDelegate implements ComponentConfigStoreDelegate {

        @Override
        public void notify(ComponentConfigEvent event) {
            String componentName = event.subject();
            String name = event.name();
            String value = event.value();

            switch (event.type()) {
                case PROPERTY_SET:
                    set(componentName, name, value);
                    break;
                case PROPERTY_UNSET:
                    reset(componentName, name);
                    break;
                default:
                    break;
            }
        }
    }

    // Locates the property in the component map and replaces it with an
    // updated copy.
    private void set(String componentName, String name, String value) {
        Map<String, ConfigProperty> map = properties.get(componentName);
        if (map != null) {
            ConfigProperty prop = map.get(name);
            if (prop != null) {
                map.put(name, ConfigProperty.setProperty(prop, value));
                triggerUpdate(componentName);
                return;
            }
        }
        log.warn("Unable to set non-existent property {} for component {}",
                 name, componentName);
    }

    // Locates the property in the component map and replaces it with an
    // reset copy.
    private void reset(String componentName, String name) {
        Map<String, ConfigProperty> map = properties.get(componentName);
        if (map != null) {
            ConfigProperty prop = map.get(name);
            if (prop != null) {
                map.put(name, ConfigProperty.resetProperty(prop));
                triggerUpdate(componentName);
                return;
            }
            log.warn("Unable to reset non-existent property {} for component {}",
                     name, componentName);
        }
    }

    // Loads existing property values that may have been set.
    private void loadExistingValues(String componentName) {
        try {
            Configuration cfg = cfgAdmin.getConfiguration(componentName, null);
            Map<String, ConfigProperty> map = properties.get(componentName);
            Dictionary<String, Object> props = cfg.getProperties();
            if (props != null) {
                Enumeration<String> it = props.keys();
                while (it.hasMoreElements()) {
                    String name = it.nextElement();
                    ConfigProperty p = map.get(name);
                    if (p != null) {
                        map.put(name, ConfigProperty.setProperty(p, (String) props.get(name)));
                    }
                }
            }
        } catch (IOException e) {
            log.error("Unable to get configuration for " + componentName, e);
        }

    }

    // FIXME: This should be a slightly deferred execution to allow changing
    // values just once per component when a number of updates arrive shortly
    // after each other.
    private void triggerUpdate(String componentName) {
        try {
            Configuration cfg = cfgAdmin.getConfiguration(componentName, null);
            Map<String, ConfigProperty> map = properties.get(componentName);
            Dictionary<String, Object> props = new Hashtable<>();
            map.values().forEach(p -> props.put(p.name(), p.value()));
            cfg.update(props);
        } catch (IOException e) {
            log.warn("Unable to update configuration for " + componentName, e);
        }
    }

}
