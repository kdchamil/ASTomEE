/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.cdi;

import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.webbeans.service.DefaultLoaderService;
import org.apache.webbeans.spi.LoaderService;
import org.apache.webbeans.spi.plugins.OpenWebBeansPlugin;

import javax.enterprise.inject.spi.Extension;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @version $Rev$ $Date$
 */
public class OptimizedLoaderService implements LoaderService {

    private static final Logger log = Logger.getInstance(LogCategory.OPENEJB.createChild("cdi"), OptimizedLoaderService.class);

    public static final ThreadLocal<Collection<String>> ADDITIONAL_EXTENSIONS = new ThreadLocal<Collection<String>>();

    private final LoaderService loaderService;

    public OptimizedLoaderService() {
        this(new DefaultLoaderService());
    }

    public OptimizedLoaderService(LoaderService loaderService) {
        this.loaderService = loaderService;
    }

    @Override
    public <T> List<T> load(Class<T> serviceType) {
        return load(serviceType, Thread.currentThread().getContextClassLoader());
    }

    @Override
    public <T> List<T> load(Class<T> serviceType, ClassLoader classLoader) {
        // ServiceLoader is expensive (can take up to a half second).  This is an optimization
        if (OpenWebBeansPlugin.class.equals(serviceType)) return loadWebBeansPlugins(classLoader);

        // As far as we know, this only is reached for CDI Extension discovery
        final List<T> list = loaderService.load(serviceType, classLoader);
        if (Extension.class.equals(serviceType)) {
            final Collection<String> additional = ADDITIONAL_EXTENSIONS.get();
            if (additional != null) {
                for (String name : additional) {
                    try {
                        list.add((T) classLoader.loadClass(name).newInstance());
                    } catch (Exception ignored) {
                        // no-op
                    }
                }
            }
        }
        return list;
    }

    private <T> List<T> loadWebBeansPlugins(final ClassLoader loader) {
        final String[] knownPlugins = {
                "org.apache.openejb.cdi.CdiPlugin",
                "org.apache.geronimo.openejb.cdi.GeronimoWebBeansPlugin"
        };
        final String[] loaderAwareKnownPlugins = {
                "org.apache.webbeans.jsf.plugin.OpenWebBeansJsfPlugin"
        };

        List<T> list = new ArrayList<T>();
        for (final String name : knownPlugins) {
            final Class<T> clazz;
            try {
                clazz = (Class<T>) loader.loadClass(name);
            } catch (final ClassNotFoundException e) {
                // ignore
                continue;
            }

            try {
                list.add(clazz.newInstance());
            } catch (final Exception e) {
                log.error("Unable to load OpenWebBeansPlugin: " + name);
            }
        }
        for (final String name : loaderAwareKnownPlugins) {
            final Class<T> clazz;
            try {
                clazz = (Class<T>) loader.loadClass(name);
            } catch (final ClassNotFoundException e) {
                // ignore
                continue;
            }

            try {
                list.add((T) Proxy.newProxyInstance(loader, new Class<?>[]{ OpenWebBeansPlugin.class }, new ClassLoaderAwareHandler(clazz.getSimpleName(), clazz.newInstance(), loader)));
            } catch (final Exception e) {
                log.error("Unable to load OpenWebBeansPlugin: " + name);
            }
        }
        return list;
    }

    private static class ClassLoaderAwareHandler implements InvocationHandler {
        private final Object delegate;
        private final ClassLoader loader;
        private final String toString;

        private ClassLoaderAwareHandler(final String toString, final Object delegate, final ClassLoader loader) {
            this.delegate = delegate;
            this.loader = loader;
            this.toString = toString;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            if (method.getName().equals("toString")) {
                return toString;
            }

            final ClassLoader old = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                return method.invoke(delegate, args);
            } finally {
                Thread.currentThread().setContextClassLoader(old);
            }
        }
    }
}
