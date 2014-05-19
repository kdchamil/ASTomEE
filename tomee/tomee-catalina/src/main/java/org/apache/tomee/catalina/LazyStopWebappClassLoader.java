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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomee.catalina;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.loader.WebappClassLoader;
import org.apache.openejb.OpenEJB;
import org.apache.openejb.classloader.ClassLoaderConfigurer;
import org.apache.openejb.classloader.WebAppEnricher;
import org.apache.openejb.config.NewLoaderLogic;
import org.apache.openejb.loader.Files;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.classloader.URLClassLoaderFirst;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;

public class LazyStopWebappClassLoader extends WebappClassLoader {
    private static final Logger LOGGER = Logger.getInstance(LogCategory.OPENEJB, LazyStopWebappClassLoader.class.getName());
    private static final ThreadLocal<ClassLoaderConfigurer> INIT_CONFIGURER = new ThreadLocal<ClassLoaderConfigurer>();

    public static final String TOMEE_WEBAPP_FIRST = "tomee.webapp-first";

    private boolean restarting = false;
    private boolean forceStopPhase = Boolean.parseBoolean(SystemInstance.get().getProperty("tomee.webappclassloader.force-stop-phase", "false"));
    private ClassLoaderConfigurer configurer = null;
    private final int hashCode;

    // ugly but break the whole container in embedded mode at least, tomcat change had a lot of side effect
    private void setj2seClassLoader(final ClassLoader loader) {
        try {
            final Field field = WebappClassLoader.class.getDeclaredField("j2seClassLoader");

            if (!field.isAccessible()) {
                field.setAccessible(true);
            }

            if (Modifier.isFinal(field.getModifiers())) {
                final Field modifiersField = Field.class.getDeclaredField("modifiers");
                modifiersField.setAccessible(true);
                modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            }

            field.set(this, loader);
        } catch (final Exception e) {
            LOGGER.warning("Can't set field j2seClassLoader");
        }
    }

    public LazyStopWebappClassLoader() {
        setj2seClassLoader(getSystemClassLoader());
        hashCode = construct();
    }

    public LazyStopWebappClassLoader(final ClassLoader parent) {
        super(parent);
        setj2seClassLoader(getSystemClassLoader());
        hashCode = construct();
    }

    private int construct() {
        setDelegate(isDelegate());
        configurer = INIT_CONFIGURER.get();
        return super.hashCode();
    }

    @Override
    public void stop() throws LifecycleException {
        // in our destroyapplication method we need a valid classloader to TomcatWebAppBuilder.afterStop()
        if (forceStopPhase && restarting) {
            internalStop();
        }
    }

    @Override
    public Class<?> loadClass(final String name) throws ClassNotFoundException {
        if ("org.apache.openejb.hibernate.OpenEJBJtaPlatform".equals(name)
                || "org.apache.openejb.jpa.integration.hibernate.PrefixNamingStrategy".equals(name)
                || "org.apache.openejb.jpa.integration.eclipselink.PrefixSessionCustomizer".equals(name)
                || "org.apache.openejb.eclipselink.JTATransactionController".equals(name)
                || "org.apache.tomee.mojarra.TomEEInjectionProvider".equals(name)) {
            // don't load them from system classloader (breaks all in embedded mode and no sense in other cases)
            synchronized (this) {
                final ClassLoader old = j2seClassLoader;
                setj2seClassLoader(NoClassClassLoader.INSTANCE);
                final boolean delegate = getDelegate();
                setDelegate(false);
                try {
                    return super.loadClass(name);
                } finally {
                    setj2seClassLoader(old);
                    setDelegate(delegate);
                }
            }
        }

        // avoid to redefine classes from server in this classloader is it not already loaded
        if (URLClassLoaderFirst.shouldDelegateToTheContainer(this, name)) { // dynamic validation handling overriding
            try {
                return OpenEJB.class.getClassLoader().loadClass(name);
            } catch (ClassNotFoundException e) {
                return super.loadClass(name);
            } catch (NoClassDefFoundError ncdfe) {
                return super.loadClass(name);
            }
        } else if (name.startsWith("javax.faces.") || name.startsWith("org.apache.webbeans.jsf.")) {
            final boolean delegate = getDelegate();
            synchronized (this) {
                setDelegate(false);
                try {
                    return super.loadClass(name);
                } finally {
                    setDelegate(delegate);
                }
            }
        }
        return super.loadClass(name);
    }

    @Override
    protected boolean validate(final String name) { // static validation, mainly container stuff
        return !URLClassLoaderFirst.shouldSkip(name);
    }

    @Override
    protected boolean filter(final String name) {
        return !"org.apache.tomee.mojarra.TomEEInjectionProvider".equals(name) && URLClassLoaderFirst.shouldSkip(name);
    }

    public void internalStop() throws LifecycleException {
        if (isStarted()) {
            // reset classloader because of tomcat classloaderlogmanager
            // to be sure we reset the right loggers
            final ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(this);
            try {
                super.stop();
            } finally {
                Thread.currentThread().setContextClassLoader(loader);
            }
        }
    }

    public void restarting() {
        restarting = true;
    }

    public void restarted() {
        restarting = false;
    }

    public boolean isRestarting() {
        return restarting;
    }

    // embeddeding implementation of sthg (JPA, JSF) can lead to classloading issues if we don't enrich the webapp
    // with our integration jars
    // typically the class will try to be loaded by the common classloader
    // but the interface implemented or the parent class
    // will be in the webapp
    @Override
    public void start() throws LifecycleException {
        super.start(); // do it first otherwise we can't use this as classloader

        // add configurer enrichments
        if (configurer != null) {
            // add now we removed all we wanted
            final URL[] enrichment = configurer.additionalURLs();
            for (final URL url : enrichment) {
                super.addURL(url);
            }
        }

        // add internal enrichments
        for (URL url : SystemInstance.get().getComponent(WebAppEnricher.class).enrichment(this)) {
            super.addURL(url);
        }
    }

    public void addURL(final URL url) {
        if (configurer == null || configurer.accept(url)) {
            super.addURL(url);
        }
    }

    @Override
    protected boolean validateJarFile(File file) throws IOException {
        return super.validateJarFile(file) && TomEEClassLoaderEnricher.validateJarFile(file) && jarIsAccepted(file);
    }

    private boolean jarIsAccepted(final File file) {
        if (configurer == null) {
            return true;
        }

        try {
            if (!configurer.accept(file.toURI().toURL())) {
                LOGGER.warning("jar '" + file.getAbsolutePath() + "' is excluded: " + file.getName() + ". It will be ignored.");
                return false;
            }
        } catch (MalformedURLException e) {
            // no-op
        }
        return true;
    }

    public static boolean isDelegate() {
        return !SystemInstance.get().getOptions().get(TOMEE_WEBAPP_FIRST, true);
    }

    @Override
    public InputStream getResourceAsStream(final String name) {
        if (!isStarted()) {
            return null;
        }
        return super.getResourceAsStream(name);
    }

    @Override
    public Enumeration<URL> getResources(final String name) throws IOException {
        if (!isStarted()) {
            return null;
        }

        if ("META-INF/services/javax.servlet.ServletContainerInitializer".equals(name)) {
            final Collection<URL> list = new ArrayList<URL>(Collections.list(super.getResources(name)));
            final Iterator<URL> it = list.iterator();
            while (it.hasNext()) {
                final URL next = it.next();
                final File file = Files.toFile(next);
                if (!file.isFile() && NewLoaderLogic.skip(next)) {
                    it.remove();
                }
            }
            return Collections.enumeration(list);
        }
        return URLClassLoaderFirst.filterResources(name, super.getResources(name));
    }

    @Override
    public boolean equals(final Object other) {
        return other != null && ClassLoader.class.isInstance(other) && hashCode() == other.hashCode();
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "LazyStop" + super.toString();
    }

    public static void initContext(final ClassLoaderConfigurer configurer) {
        INIT_CONFIGURER.set(configurer);
    }

    public static void cleanInitContext() {
        INIT_CONFIGURER.remove();
    }

    private static class NoClassClassLoader extends ClassLoader {
        private static final NoClassClassLoader INSTANCE = new NoClassClassLoader();

        @Override
        public Class<?> loadClass(final String name) throws ClassNotFoundException {
            throw new ClassNotFoundException();
        }
    }
}
