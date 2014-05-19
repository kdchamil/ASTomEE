/**
 *
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
package org.apache.openejb.server.cxf;

import org.apache.cxf.Bus;
import org.apache.openejb.BeanContext;
import org.apache.openejb.assembler.classic.util.ServiceConfiguration;
import org.apache.openejb.core.webservices.PortData;
import org.apache.openejb.server.cxf.client.SaajInterceptor;
import org.apache.openejb.server.cxf.ejb.EjbWsContainer;
import org.apache.openejb.server.cxf.pojo.PojoWsContainer;
import org.apache.openejb.server.cxf.transport.HttpTransportFactory;
import org.apache.openejb.server.cxf.transport.util.CxfUtil;
import org.apache.openejb.server.httpd.HttpListener;
import org.apache.openejb.server.webservices.WsService;

import javax.naming.Context;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

public class CxfService extends WsService {
    public static final String OPENEJB_JAXWS_CXF_FEATURES = "features";

    private final Map<String, CxfWsContainer> wsContainers = new TreeMap<String, CxfWsContainer>();

    private HttpTransportFactory httpTransportFactory;

    public String getName() {
        return "cxf";
    }

    public void init(final Properties props) throws java.lang.Exception {
        super.init(props);
        CxfUtil.configureBus();
        SaajInterceptor.registerInterceptors();

        initBusTransport(CxfUtil.getBus());
    }

    private void initBusTransport(final Bus bus) {
        httpTransportFactory = new HttpTransportFactory(bus);
        final List<String> ids = new ArrayList<String>(httpTransportFactory.getTransportIds().size() + 1);
        ids.addAll(httpTransportFactory.getTransportIds());
        ids.add("http://schemas.xmlsoap.org/wsdl/soap/");
        httpTransportFactory.setTransportIds(ids);
    }

    @Override
    protected void setWsdl(final HttpListener listener, final String wsdl) {
        if (CxfWsContainer.class.isInstance(listener)) {
            CxfWsContainer.class.cast(listener).setWsldUrl(wsdl);
        }
    }

    protected HttpListener createEjbWsContainer(URL moduleBaseUrl, PortData port, BeanContext beanContext, ServiceConfiguration config) {
        final Bus bus = CxfUtil.getBus();

        final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(CxfUtil.initBusLoader());
        try {
            CxfCatalogUtils.loadOASISCatalog(bus, moduleBaseUrl, "META-INF/jax-ws-catalog.xml");

            final EjbWsContainer container = new EjbWsContainer(bus, httpTransportFactory, port, beanContext, config);
            container.start();
            wsContainers.put(beanContext.getDeploymentID().toString(), container);
            return container;
        } finally {
            if (oldLoader != null) {
                CxfUtil.clearBusLoader(oldLoader);
            }
        }
    }

    protected HttpListener createPojoWsContainer(ClassLoader loader, URL moduleBaseUrl, PortData port, String serviceId, Class target, Context context, String contextRoot, Map<String, Object> bdgs, ServiceConfiguration services) {
        Bus bus = CxfUtil.getBus();

        final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(CxfUtil.initBusLoader());
        try {
            CxfCatalogUtils.loadOASISCatalog(bus, moduleBaseUrl, "META-INF/jax-ws-catalog.xml");

            PojoWsContainer container = new PojoWsContainer(loader, httpTransportFactory, bus, port, context, target, bdgs, services);
            container.start();
            wsContainers.put(serviceId, container);
            return container;
        } finally {
            if (oldLoader != null) {
                CxfUtil.clearBusLoader(oldLoader);
            }
        }
    }

    protected void destroyPojoWsContainer(final String serviceId) {
        destroyWsContainer(serviceId);
    }

    protected void destroyEjbWsContainer(final String deploymentId) {
        destroyWsContainer(deploymentId);
    }

    protected void destroyWsContainer(final String serviceId) {
        CxfWsContainer container = wsContainers.remove(serviceId);
        if (container != null) {
            final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(CxfUtil.initBusLoader());
            try {
                container.destroy();
            } finally {
                if (oldLoader != null) {
                    CxfUtil.clearBusLoader(oldLoader);
                }
            }
        }
    }
}
