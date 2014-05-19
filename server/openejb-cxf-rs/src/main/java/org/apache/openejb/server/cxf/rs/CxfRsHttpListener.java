/*
 *     Licensed to the Apache Software Foundation (ASF) under one or more
 *     contributor license agreements.  See the NOTICE file distributed with
 *     this work for additional information regarding copyright ownership.
 *     The ASF licenses this file to You under the Apache License, Version 2.0
 *     (the "License"); you may not use this file except in compliance with
 *     the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */
package org.apache.openejb.server.cxf.rs;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.endpoint.ServerImpl;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.apache.cxf.jaxrs.JAXRSServiceImpl;
import org.apache.cxf.jaxrs.ext.RequestHandler;
import org.apache.cxf.jaxrs.ext.ResourceComparator;
import org.apache.cxf.jaxrs.lifecycle.ResourceProvider;
import org.apache.cxf.jaxrs.lifecycle.SingletonResourceProvider;
import org.apache.cxf.jaxrs.model.ClassResourceInfo;
import org.apache.cxf.jaxrs.model.MethodDispatcher;
import org.apache.cxf.jaxrs.model.OperationResourceInfo;
import org.apache.cxf.jaxrs.model.ProviderInfo;
import org.apache.cxf.jaxrs.model.wadl.WadlGenerator;
import org.apache.cxf.jaxrs.provider.JAXBElementProvider;
import org.apache.cxf.jaxrs.provider.json.JSONProvider;
import org.apache.cxf.service.invoker.Invoker;
import org.apache.cxf.transport.http.AbstractHTTPDestination;
import org.apache.cxf.transport.http.HTTPTransportFactory;
import org.apache.cxf.transport.servlet.BaseUrlHelper;
import org.apache.openejb.BeanContext;
import org.apache.openejb.Injection;
import org.apache.openejb.api.internal.Internal;
import org.apache.openejb.api.jmx.Description;
import org.apache.openejb.api.jmx.MBean;
import org.apache.openejb.api.jmx.ManagedAttribute;
import org.apache.openejb.api.jmx.ManagedOperation;
import org.apache.openejb.assembler.classic.ServiceInfo;
import org.apache.openejb.assembler.classic.util.ServiceConfiguration;
import org.apache.openejb.assembler.classic.util.ServiceInfos;
import org.apache.openejb.loader.IO;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.monitoring.LocalMBeanServer;
import org.apache.openejb.monitoring.ObjectNameBuilder;
import org.apache.openejb.rest.ThreadLocalContextManager;
import org.apache.openejb.server.cxf.transport.util.CxfUtil;
import org.apache.openejb.server.httpd.HttpRequest;
import org.apache.openejb.server.httpd.HttpRequestImpl;
import org.apache.openejb.server.httpd.HttpResponse;
import org.apache.openejb.server.rest.EJBRestServiceInfo;
import org.apache.openejb.server.rest.RsHttpListener;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.proxy.ProxyEJB;
import org.apache.webbeans.config.WebBeansContext;

import javax.management.ObjectName;
import javax.management.openmbean.TabularData;
import javax.naming.Context;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Application;
import javax.xml.bind.Marshaller;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class CxfRsHttpListener implements RsHttpListener {
    private static final Logger LOGGER = Logger.getInstance(LogCategory.OPENEJB_RS, CxfRsHttpListener.class);

    private static final java.util.logging.Logger SERVER_IMPL_LOGGER = LogUtils.getL7dLogger(ServerImpl.class);

    public static final String CXF_JAXRS_PREFIX = "cxf.jaxrs.";
    public static final String PROVIDERS_KEY = CXF_JAXRS_PREFIX + "providers";
    public static final String STATIC_RESOURCE_KEY = CXF_JAXRS_PREFIX + "static-resources-list";
    public static final String STATIC_SUB_RESOURCE_RESOLUTION_KEY = "staticSubresourceResolution";
    public static final String RESOURCE_COMPARATOR_KEY = CXF_JAXRS_PREFIX + "resourceComparator";

    private static final String GLOBAL_PROVIDERS = SystemInstance.get().getProperty(PROVIDERS_KEY);

    private static final Map<String, String> STATIC_CONTENT_TYPES;

    private final HTTPTransportFactory transportFactory;
    private final String wildcard;
    private AbstractHTTPDestination destination;
    private Server server;
    private String context = "";
    private Collection<Pattern> staticResourcesList = new CopyOnWriteArrayList<Pattern>();
    private List<ObjectName> jmxNames = new ArrayList<ObjectName>();

    static {
        STATIC_CONTENT_TYPES = new HashMap<String, String>();
        STATIC_CONTENT_TYPES.put("html", "text/html");
        STATIC_CONTENT_TYPES.put("xhtml", "text/html");
        STATIC_CONTENT_TYPES.put("txt", "text/plain");
        STATIC_CONTENT_TYPES.put("css", "text/css");
        STATIC_CONTENT_TYPES.put("jpg", "image/jpg");
        STATIC_CONTENT_TYPES.put("png", "image/png");
        STATIC_CONTENT_TYPES.put("ico", "image/ico");
        STATIC_CONTENT_TYPES.put("pdf", "application/pdf");
        STATIC_CONTENT_TYPES.put("xsd", "application/xml");

        // CXF-5319: bug in CXF? it prevents to get the wadl as json otherwise
        if ("true".equalsIgnoreCase(SystemInstance.get().getProperty("openejb.cxf-rs.wadl-generator.ignoreMessageWriters", "false"))) {
            for (final ProviderInfo<RequestHandler> rh : org.apache.cxf.jaxrs.provider.ProviderFactory.getSharedInstance().getRequestHandlers()) {
                final RequestHandler provider = rh.getProvider();
                if (WadlGenerator.class.isInstance(provider)) {
                    WadlGenerator.class.cast(provider).setIgnoreMessageWriters(false);
                }
            }
        }
    }

    public CxfRsHttpListener(final HTTPTransportFactory httpTransportFactory, final String star) {
        transportFactory = httpTransportFactory;
        wildcard = star;
    }

    @Override
    public void onMessage(final HttpRequest httpRequest, final HttpResponse httpResponse) throws Exception {
        if (matchPath(httpRequest)) {
            serveStaticContent(httpRequest, httpResponse, httpRequest.getPathInfo());
            return;
        }

        // fix the address (to manage multiple connectors)
        if (httpRequest instanceof HttpRequestImpl) {
            ((HttpRequestImpl) httpRequest).initPathFromContext(context);
        }


        String baseURL = BaseUrlHelper.getBaseURL(httpRequest);
        if (!baseURL.endsWith("/")) {
            baseURL += "/";
        }

        httpRequest.setAttribute("org.apache.cxf.transport.endpoint.address", baseURL);

        // delegate invocation
        final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(CxfUtil.initBusLoader());
        try {
            destination.invoke(null, httpRequest.getServletContext(), new HttpServletRequestWrapper(httpRequest) {
                // see org.apache.cxf.jaxrs.utils.HttpUtils.getPathToMatch()
                // cxf uses implicitly getRawPath() from the endpoint but not for the request URI
                // so without stripping the address until the context the behavior is weird
                // this is just a workaround waiting for something better
                @Override
                public String getRequestURI() {
                    if (httpRequest instanceof HttpRequestImpl) {
                        return strip(context, ((HttpRequestImpl) httpRequest).requestRawPath());
                    }
                    return strip(context, super.getRequestURI());
                }
            }, httpResponse);
        } finally {
            if (oldLoader != null) {
                CxfUtil.clearBusLoader(oldLoader);
            }
        }
    }

    private boolean matchPath(final HttpServletRequest request) {
        if (staticResourcesList.isEmpty()) {
            return false;
        }

        String path = request.getPathInfo();
        if (path == null) {
            path = "/";
        }
        for (Pattern pattern : staticResourcesList) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    protected void serveStaticContent(final HttpServletRequest request,
                                      final HttpServletResponse response,
                                      final String pathInfo) throws ServletException {
        final InputStream is = request.getServletContext().getResourceAsStream(pathInfo);
        if (is == null) {
            throw new ServletException("Static resource " + pathInfo + " is not available");
        }
        try {
            int ind = pathInfo.lastIndexOf(".");
            if (ind != -1 && ind < pathInfo.length()) {
                String type = STATIC_CONTENT_TYPES.get(pathInfo.substring(ind + 1));
                if (type != null) {
                    response.setContentType(type);
                }
            }

            final ServletOutputStream os = response.getOutputStream();
            IOUtils.copy(is, os);
            os.flush();
        } catch (IOException ex) {
            throw new ServletException("Static resource " + pathInfo + " can not be written to the output stream");
        }

    }

    private String strip(final String context, final String requestURI) {
        if (requestURI.startsWith(context)) {
            return requestURI.substring(context.length());
        }
        return requestURI;
    }

    @Override
    public void deploySingleton(String contextRoot, String fullContext, Object o, Application appInstance,
                                Collection<Object> additionalProviders, ServiceConfiguration configuration) {
        deploy(contextRoot, o.getClass(), fullContext, new SingletonResourceProvider(o), o, appInstance, null, additionalProviders, configuration);
    }

    @Override
    public void deployPojo(ClassLoader loader, String contextRoot, String fullContext, Class<?> loadedClazz, Application app, Collection<Injection> injections,
                           Context context, WebBeansContext owbCtx, Collection<Object> additionalProviders, ServiceConfiguration configuration) {
        deploy(contextRoot, loadedClazz, fullContext, new OpenEJBPerRequestPojoResourceProvider(loader, loadedClazz, injections, context, owbCtx),
                            null, app, null, additionalProviders, configuration);
    }

    @Override
    public void deployEJB(String contextRoot, String fullContext, BeanContext beanContext, Collection<Object> additionalProviders, ServiceConfiguration configuration) {
        final Object proxy = ProxyEJB.subclassProxy(beanContext);

        deploy(contextRoot, beanContext.getBeanClass(), fullContext, new NoopResourceProvider(beanContext.getBeanClass(), proxy),
                proxy, null, new OpenEJBEJBInvoker(Collections.singleton(beanContext)), additionalProviders, configuration);
    }

    private void deploy(String contextRoot, Class<?> clazz, String address, ResourceProvider rp, Object serviceBean,
                        Application app, Invoker invoker, Collection<Object> additionalProviders, ServiceConfiguration configuration) {
        final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(CxfUtil.initBusLoader());
        try {
            final JAXRSServerFactoryBean factory = newFactory(address);
            configureFactory(additionalProviders, configuration, factory);
            factory.setResourceClasses(clazz);
            context = contextRoot;
            if (context == null) {
                context = "";
            }
            if (!context.startsWith("/")) {
                context = "/" + context;
            }

            if (rp != null) {
                factory.setResourceProvider(rp);
            }
            if (app != null) {
                factory.setApplication(app);
            }
            if (invoker != null) {
                factory.setInvoker(invoker);
            }
            if (serviceBean != null) {
                factory.setServiceBean(serviceBean);
            } else {
                factory.setServiceClass(clazz);
            }

            server = factory.create();
            destination = (AbstractHTTPDestination) server.getDestination();
        } finally {
            if (oldLoader != null) {
                CxfUtil.clearBusLoader(oldLoader);
            }
        }
    }

    private Collection<Object> providers(final Collection<ServiceInfo> services, final Collection<Object> additionalProviders) {
        final Collection<Object> instances = new ArrayList<Object>();
        for (Object o : additionalProviders) {
            if (o instanceof Class<?>) {
                final Class<?> clazz = (Class<?>) o;
                final Collection<Object> instance = ServiceInfos.resolve(services, new String[] { clazz.getName() }, ProviderFactory.INSTANCE);
                if (instance != null && !instance.isEmpty()) {
                    instances.add(instance.iterator().next());
                } else {
                    try {
                        instances.add(newProvider(clazz));
                    } catch (final Exception e) {
                        LOGGER.error("can't instantiate " + clazz.getName(), e);
                    }
                }
            } else {
                instances.add(o);
            }
        }
        return instances;
    }

    private Object newProvider(final Class<?> clazz) throws IllegalAccessException, InstantiationException {
        return clazz.newInstance();
    }

    public void undeploy() {
        // unregister all MBeans
        for (final ObjectName objectName : jmxNames) {
            LocalMBeanServer.unregisterSilently(objectName);
        }

        final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(CxfUtil.initBusLoader());
        try {
            server.stop();
        } catch (final RuntimeException ise) {
            LOGGER.warning("Can't stop correctly the endpoint " + server);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(ise.getMessage(), ise);
            }
        } finally {
            if (oldLoader != null) {
                CxfUtil.clearBusLoader(oldLoader);
            }
        }
    }

    @Override
    public void deployApplication(final Application application, final String prefix, final String webContext,
                                  final Collection<Object> additionalProviders,
                                  final Map<String, EJBRestServiceInfo> restEjbs, final ClassLoader classLoader,
                                  final Collection<Injection> injections, final Context context, final WebBeansContext owbCtx,
                                  final ServiceConfiguration serviceConfiguration) {
        final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(CxfUtil.initBusLoader());
        try {
            final JAXRSServerFactoryBean factory = newFactory(prefix);
            configureFactory(additionalProviders, serviceConfiguration, factory);
            factory.setApplication(application);

            final List<Class<?>> classes = new ArrayList<Class<?>>();

            for (Class<?> clazz : application.getClasses()) {
                if (!additionalProviders.contains(clazz) && !clazz.isInterface()) {
                    classes.add(clazz);
                }
            }

            for (Object o : application.getSingletons()) {
                if (!additionalProviders.contains(o)) {
                    final Class<?> clazz = o.getClass();
                    classes.add(clazz);
                }
            }

            for (Class<?> clazz : classes) {
                final String name = clazz.getName();
                if (restEjbs.containsKey(name)) {
                    final BeanContext bc = restEjbs.get(name).context;
                    final Object proxy = ProxyEJB.subclassProxy(bc);
                    factory.setResourceProvider(clazz, new NoopResourceProvider(bc.getBeanClass(), proxy));
                } else {
                    factory.setResourceProvider(clazz, new OpenEJBPerRequestPojoResourceProvider(classLoader, clazz, injections, context, owbCtx));
                }
            }

            factory.setResourceClasses(classes);
            factory.setInvoker(new AutoJAXRSInvoker(restEjbs));

            final Level level = SERVER_IMPL_LOGGER.getLevel();
            SERVER_IMPL_LOGGER.setLevel(Level.OFF);
            try {
                server = factory.create();
            } finally {
                SERVER_IMPL_LOGGER.setLevel(level);
            }

            this.context = webContext;
            if (!webContext.startsWith("/")) {
                this.context = "/" + webContext;
            }
            destination = (AbstractHTTPDestination) server.getDestination();

            final String base;
            if (prefix.endsWith("/")) {
                base = prefix.substring(0, prefix.length() - 1);
            } else if (prefix.endsWith(wildcard)) {
                base = prefix.substring(0, prefix.length() - wildcard.length());
            } else {
                base = prefix;
            }

            // stack info to log to get nice logs
            logEndpoints(application, prefix, restEjbs, factory, base);
        } finally {
            if (oldLoader != null) {
                CxfUtil.clearBusLoader(oldLoader);
            }
        }
    }

    private void logEndpoints(final Application application, final String prefix,
                              final Map<String, EJBRestServiceInfo> restEjbs,
                              final JAXRSServerFactoryBean factory, final String base) {
        final List<Logs.LogResourceEndpointInfo> resourcesToLog = new ArrayList<Logs.LogResourceEndpointInfo>();
        int classSize = 0;
        int addressSize = 0;

        final JAXRSServiceImpl service = (JAXRSServiceImpl) factory.getServiceFactory().getService();
        final List<ClassResourceInfo> resources = service.getClassResourceInfos();
        for (final ClassResourceInfo info : resources) {
            if (info.getResourceClass() == null) { // possible?
                continue;
            }

            final String address = Logs.singleSlash(base, info.getURITemplate().getValue());

            String clazz = info.getResourceClass().getName();
            final String type;
            if (restEjbs.containsKey(clazz)) {
                type = "EJB";
            } else {
                type = "Pojo";
            }

            classSize = Math.max(classSize, clazz.length());
            addressSize = Math.max(addressSize, address.length());

            int methodSize = 7;
            int methodStrSize = 0;

            final List<Logs.LogOperationEndpointInfo> toLog = new ArrayList<Logs.LogOperationEndpointInfo>();

            final MethodDispatcher md = info.getMethodDispatcher();
            for (OperationResourceInfo ori : md.getOperationResourceInfos()) {
                final String httpMethod = ori.getHttpMethod();
                final String currentAddress = Logs.singleSlash(address, ori.getURITemplate().getValue());
                final String methodToStr = Logs.toSimpleString(ori.getMethodToInvoke());
                toLog.add(new Logs.LogOperationEndpointInfo(httpMethod, currentAddress, methodToStr));

                if (httpMethod != null) {
                    methodSize = Math.max(methodSize, httpMethod.length());
                }
                addressSize = Math.max(addressSize, currentAddress.length());
                methodStrSize = Math.max(methodStrSize, methodToStr.length());
            }

            Collections.sort(toLog);

            resourcesToLog.add(new Logs.LogResourceEndpointInfo(type, address, clazz, toLog, methodSize, methodStrSize));
        }

        // effective logging

        LOGGER.info("REST Application: " + Logs.forceLength(prefix, addressSize, true) + " -> " + application.getClass().getName());

        Collections.sort(resourcesToLog);

        for (final Logs.LogResourceEndpointInfo resource : resourcesToLog) {

            // Init and register MBeans
            final ObjectNameBuilder jmxName = new ObjectNameBuilder("openejb.management")
                    .set("j2eeType", "JAX-RS")
                    .set("J2EEServer", "openejb")
                    .set("J2EEApplication", base)
                    .set("EndpointType", resource.type)
                    .set("name", resource.classname);

            ObjectName jmxObjectName = jmxName.build();
            LocalMBeanServer.registerDynamicWrapperSilently(
                    new RestServiceMBean(resource),
                    jmxObjectName);

            jmxNames.add(jmxObjectName);

            LOGGER.info("     Service URI: "
                    + Logs.forceLength(resource.address, addressSize, true) + " -> "
                    + Logs.forceLength(resource.type, 4, false) + " "
                    + Logs.forceLength(resource.classname, classSize, true));

            for (Logs.LogOperationEndpointInfo log : resource.operations) {
                LOGGER.info("          "
                        + Logs.forceLength(log.http, resource.methodSize, false) + " "
                        + Logs.forceLength(log.address, addressSize, true) + " ->      "
                        + Logs.forceLength(log.method, resource.methodStrSize, true));
            }

            resource.operations.clear();
        }
        resourcesToLog.clear();
    }

    private JAXRSServerFactoryBean newFactory(String prefix) {
        final JAXRSServerFactoryBean factory = new JAXRSServerFactoryBean();
        factory.setDestinationFactory(transportFactory);
        factory.setBus(transportFactory.getBus());
        factory.setAddress(prefix);
        return factory;
    }

    private void configureFactory(Collection<Object> additionalProviders, ServiceConfiguration serviceConfiguration, JAXRSServerFactoryBean factory) {
        CxfUtil.configureEndpoint(factory, serviceConfiguration, CXF_JAXRS_PREFIX);

        final Collection<ServiceInfo> services = serviceConfiguration.getAvailableServices();

        final String staticSubresourceResolution = serviceConfiguration.getProperties().getProperty(CXF_JAXRS_PREFIX + STATIC_SUB_RESOURCE_RESOLUTION_KEY);
        if (staticSubresourceResolution != null) {
            factory.setStaticSubresourceResolution("true".equalsIgnoreCase(staticSubresourceResolution));
        }

        // resource comparator
        final String resourceComparator = serviceConfiguration.getProperties().getProperty(RESOURCE_COMPARATOR_KEY);
        if (resourceComparator != null) {
            try {
                ResourceComparator instance = (ResourceComparator) ServiceInfos.resolve(services, resourceComparator);
                if (instance == null) {
                    instance = (ResourceComparator) Thread.currentThread().getContextClassLoader()
                            .loadClass(resourceComparator).newInstance();
                }
                factory.setResourceComparator(instance);
            } catch (Exception e) {
                LOGGER.error("Can't create the resource comparator " + resourceComparator, e);
            }
        }

        // static resources
        final String staticResources = serviceConfiguration.getProperties().getProperty(STATIC_RESOURCE_KEY);
        if (staticResources != null) {
            final String[] resources = staticResources.split(",");
            for (String r : resources) {
                final String trimmed = r.trim();
                if (!trimmed.isEmpty()) {
                    staticResourcesList.add(Pattern.compile(trimmed));
                }
            }
        }

        // providers
        Set<String> providersConfig = null;

        {
            final String provider = serviceConfiguration.getProperties().getProperty(PROVIDERS_KEY);
            if (provider != null) {
                providersConfig = new HashSet<String>();
                providersConfig.addAll(Arrays.asList(provider.split(",")));
            }

            {
                if (GLOBAL_PROVIDERS != null) {
                    if (providersConfig == null) {
                        providersConfig = new HashSet<String>();
                    }
                    providersConfig.addAll(Arrays.asList(GLOBAL_PROVIDERS.split(",")));
                }
            }
        }


        List<Object> providers = null;
        if (providersConfig != null) {
            providers = ServiceInfos.resolve(services, providersConfig.toArray(new String[providersConfig.size()]), ProviderFactory.INSTANCE);
            if (providers != null && additionalProviders != null && !additionalProviders.isEmpty()) {
                providers.addAll(providers(services, additionalProviders));
            }
            factory.setProviders(providers);
        }
        if (providers == null) {
            providers = new ArrayList<Object>();
            if (additionalProviders != null && !additionalProviders.isEmpty()) {
                providers.addAll(providers(services, additionalProviders));
            } else {
                providers.addAll(defaultProviders());
            }
            factory.setProviders(providers);
        } else {
            LOGGER.info("Using providers " + providers);
        }
    }

    private static List<Object> defaultProviders() {
        final JAXBElementProvider jaxb = new JAXBElementProvider();
        final Map<String, Object> jaxbProperties = new HashMap<String, Object> ();
        jaxbProperties.put(Marshaller.JAXB_FRAGMENT, true);
        jaxb.setMarshallerProperties(jaxbProperties);

        final JSONProvider json = new JSONProvider();
        // TOMEE-514
        // json.setSerializeAsArray(true);

        return Arrays.asList((Object) jaxb, json);
    }

    private static class ProviderFactory implements ServiceInfos.Factory {
        private static final ServiceInfos.Factory INSTANCE = new ProviderFactory();

        @Override
        public Object newInstance(final Class<?> clazz) throws Exception {
            boolean found = false;
            Object instance = null;
            for (final Constructor<?> c : clazz.getConstructors()) {
                int contextAnnotations = 0;
                for (final Annotation[] annotations : c.getParameterAnnotations()) {
                    for (final Annotation a : annotations) {
                        if (javax.ws.rs.core.Context.class.equals(a.annotationType())) {
                            contextAnnotations++;
                            break;
                        }
                    }
                }
                if (contextAnnotations == c.getParameterTypes().length) {
                    if (found) {
                        LOGGER.warning("Found multiple matching constructor for " + clazz.getName());
                        return instance;
                    }

                    final Object[] params = new Object[c.getParameterTypes().length];
                    for (int i = 0; i < params.length; i++) {
                        params[i] = ThreadLocalContextManager.findThreadLocal(c.getParameterTypes()[i]);
                        // params[i] can be null if not a known type
                    }
                    instance = c.newInstance(params);
                    found = true;
                }
            }
            if (instance != null) {
                return instance;
            }
            return clazz.newInstance();
        }
    }

    @MBean
    @Internal
    @Description("JAX-RS service information")
    public class RestServiceMBean {

        private String type;
        private String address;
        private String classname;
        private TabularData operations;

        public RestServiceMBean(final Logs.LogResourceEndpointInfo jmxName) {
            this.type = jmxName.type;
            this.address = jmxName.address;
            this.classname = jmxName.classname;

            final String[] names = new String[jmxName.operations.size()];
            final String[] values = new String[jmxName.operations.size()];
            int idx = 0;
            for (final Logs.LogOperationEndpointInfo operation : jmxName.operations) {
                names[idx] = operation.http + " " + operation.address;
                values[idx] = operation.method;
                idx++;
            }
            operations = LocalMBeanServer.tabularData("Operations", "Operations for this endpoint", names, values);
        }

        @ManagedAttribute
        @Description("The type of the REST service")
        public String getWadlUrl() {
            if (address.endsWith("?_wadl")) {
                return address;
            }
            return address + "?_wadl";
        }

        @ManagedOperation
        @Description("The type of the REST service")
        public String getWadl(final String format) {
            if (format != null && format.toLowerCase().contains("json")) {
                InputStream inputStream = null;
                try {
                    final URL url = new URL(getWadlUrl() + "&_type=json");
                    final HttpURLConnection connection = HttpURLConnection.class.cast(url.openConnection());
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("Content-type", "application/json");
                    inputStream = connection.getInputStream();
                    return IO.slurp(inputStream);
                } catch (final Exception e) {
                    return e.getMessage();
                } finally {
                    IO.close(inputStream);
                }
            } else { // xml
                try {
                    return IO.slurp(new URL(getWadlUrl()));
                } catch (final IOException e) {
                    return e.getMessage();
                }
            }
        }

        @ManagedAttribute
        @Description("The type of the REST service")
        public String getType() {
            return type;
        }

        @ManagedAttribute
        @Description("The REST service address")
        public String getAddress() {
            return address;
        }

        @ManagedAttribute
        @Description("The REST service class name")
        public String getClassname() {
            return classname;
        }

        @ManagedAttribute
        @Description("All available methods")
        public TabularData getOperations() {
            return operations;
        }
    }
}
