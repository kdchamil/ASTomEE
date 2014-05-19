/*
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
package org.apache.openejb.core;

import org.apache.openejb.AppContext;
import org.apache.openejb.Injection;
import org.apache.openejb.InjectionProcessor;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.cdi.ConstructorInjectionBean;
import org.apache.webbeans.component.InjectionTargetBean;
import org.apache.webbeans.component.WebBeansType;
import org.apache.webbeans.config.WebBeansContext;

import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionListener;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebContext {
    private String id;
    private ClassLoader classLoader;
    private final Collection<Injection> injections = new ArrayList<Injection>();
    private Context jndiEnc;
    private final AppContext appContext;
    private Map<String,Object> bindings;
    private Map<Object, CreationalContext<?>> creatonalContexts = new ConcurrentHashMap<Object, CreationalContext<?>>();
    private WebBeansContext webbeansContext;
    private String contextRoot;
    private String host;
    private Context initialContext;
    private final Map<Class<?>, ConstructorInjectionBean<Object>> constructorInjectionBeanCache = new ConcurrentHashMap<Class<?>, ConstructorInjectionBean<Object>>();

    public Context getInitialContext() {
        if (initialContext != null) return initialContext;
        try {
            initialContext = (Context) new InitialContext().lookup("java:");
        } catch (NamingException e) {
            throw new IllegalStateException(e);
        }
        return initialContext;
    }

    public void setHost(final String host) {
        this.host = host;
    }

    public String getHost() {
        return host;
    }

    public void setInitialContext(final Context initialContext) {
        this.initialContext = initialContext;
    }

    public WebContext(AppContext appContext) {
        this.appContext = appContext;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public Collection<Injection> getInjections() {
        return injections;
    }

    public Context getJndiEnc() {
        return jndiEnc;
    }

    public void setJndiEnc(Context jndiEnc) {
        this.jndiEnc = jndiEnc;
    }

    public AppContext getAppContext() {
        return appContext;
    }

    public Object newInstance(Class beanClass) throws OpenEJBException {

        final WebBeansContext webBeansContext = getWebBeansContext();
        final ConstructorInjectionBean<Object> beanDefinition = getConstructorInjectionBean(beanClass, webBeansContext);
        final CreationalContext<Object> creationalContext;
        final Object o;
        if (webBeansContext == null) {
            creationalContext = null;
            try {
                o = beanClass.newInstance();
            } catch (final InstantiationException e) {
                throw new OpenEJBException(e);
            } catch (final IllegalAccessException e) {
                throw new OpenEJBException(e);
            }
        } else {
            creationalContext = webBeansContext.getBeanManagerImpl().createCreationalContext(beanDefinition);
            o = beanDefinition.create(creationalContext);
        }

        // Create bean instance
        final Context unwrap = InjectionProcessor.unwrap(getInitialContext());
        final InjectionProcessor injectionProcessor = new InjectionProcessor(o, injections, unwrap);

        final Object beanInstance = injectionProcessor.createInstance();

        if (webBeansContext != null) {
            InjectionTargetBean<Object> bean = InjectionTargetBean.class.cast(beanDefinition);
            bean.getInjectionTarget().inject(beanInstance, creationalContext);

            creatonalContexts.put(beanInstance, creationalContext);
        }
        return beanInstance;
    }

    private ConstructorInjectionBean<Object> getConstructorInjectionBean(final Class beanClass, final WebBeansContext webBeansContext) {
        if (webBeansContext == null) {
            return null;
        }

        ConstructorInjectionBean<Object> beanDefinition = constructorInjectionBeanCache.get(beanClass);
        if (beanDefinition == null) {
            synchronized (this) {
                beanDefinition = constructorInjectionBeanCache.get(beanClass);
                if (beanDefinition == null) {
                    final AnnotatedType annotatedType = webBeansContext.getAnnotatedElementFactory().newAnnotatedType(beanClass);
                    if (isWeb(beanClass)) {
                        beanDefinition = new ConstructorInjectionBean<Object>(webBeansContext, beanClass, annotatedType, false);
                    } else {
                        beanDefinition = new ConstructorInjectionBean<Object>(webBeansContext, beanClass, annotatedType);
                    }

                    constructorInjectionBeanCache.put(beanClass, beanDefinition);
                }
            }
        }
        return beanDefinition;
    }

    private static boolean isWeb(final Class<?> beanClass) {
        return Servlet.class.isAssignableFrom(beanClass)
                || Filter.class.isAssignableFrom(beanClass)
                || HttpSessionAttributeListener.class.isAssignableFrom(beanClass)
                || ServletContextListener.class.isAssignableFrom(beanClass)
                || HttpSessionAttributeListener.class.isAssignableFrom(beanClass)
                || ServletRequestListener.class.isAssignableFrom(beanClass)
                || ServletContextAttributeListener.class.isAssignableFrom(beanClass)
                || ServletRequestAttributeListener.class.isAssignableFrom(beanClass);
    }

    public WebBeansContext getWebBeansContext() {
        if (webbeansContext == null) {
            return getAppContext().getWebBeansContext();
        }
        return webbeansContext;
    }

    public Object inject(final Object o) throws OpenEJBException {

        try {
            final WebBeansContext webBeansContext = getWebBeansContext();

            // Create bean instance
            final Context initialContext = (Context) new InitialContext().lookup("java:");
            final Context unwrap = InjectionProcessor.unwrap(initialContext);
            final InjectionProcessor injectionProcessor = new InjectionProcessor(o, injections, unwrap);

            final Object beanInstance = injectionProcessor.createInstance();

            if (webBeansContext != null) {
                final ConstructorInjectionBean<Object> beanDefinition = getConstructorInjectionBean(o.getClass(), webBeansContext);
                final CreationalContext<Object> creationalContext = webBeansContext.getBeanManagerImpl().createCreationalContext(beanDefinition);

                InjectionTargetBean<Object> bean = InjectionTargetBean.class.cast(beanDefinition);
                bean.getInjectionTarget().inject(beanInstance, creationalContext);

                // if the bean is dependent simply cleanup the creational context once it is created
                final Class<? extends Annotation> scope = beanDefinition.getScope();
                if (scope == null || Dependent.class.equals(scope)) {
                    creatonalContexts.put(beanInstance, creationalContext);
                }
            }

            return beanInstance;
        } catch (NamingException e) {
            throw new OpenEJBException(e);
        }
    }

    public void setBindings(Map<String, Object> bindings) {
        this.bindings = bindings;
    }

    public Map<String, Object> getBindings() {
        return bindings;
    }

    public void setWebbeansContext(WebBeansContext webbeansContext) {
        this.webbeansContext = webbeansContext;
    }

    public WebBeansContext getWebbeansContext() {
        return webbeansContext;
    }

    public void setContextRoot(String contextRoot) {
        this.contextRoot = contextRoot;
    }

    public String getContextRoot() {
        return contextRoot;
    }

    public void destroy(final Object o) {
        final CreationalContext<?> ctx = creatonalContexts.remove(o);
        if (ctx != null) {
            ctx.release();
        }
    }
}
