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
package org.apache.openejb.core.stateless;

import org.apache.openejb.ApplicationException;
import org.apache.openejb.BeanContext;
import org.apache.openejb.ContainerType;
import org.apache.openejb.InterfaceType;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.ProxyInfo;
import org.apache.openejb.SystemException;
import org.apache.openejb.cdi.CurrentCreationalContext;
import org.apache.openejb.core.ExceptionType;
import org.apache.openejb.core.Operation;
import org.apache.openejb.core.ThreadContext;
import org.apache.openejb.core.interceptor.InterceptorData;
import org.apache.openejb.core.interceptor.InterceptorStack;
import org.apache.openejb.core.timer.EjbTimerService;
import org.apache.openejb.core.transaction.TransactionPolicy;
import org.apache.openejb.core.webservices.AddressingSupport;
import org.apache.openejb.core.webservices.NoAddressingSupport;
import org.apache.openejb.monitoring.StatsInterceptor;
import org.apache.openejb.spi.SecurityService;
import org.apache.openejb.util.Duration;
import org.apache.openejb.util.Pool;
import org.apache.xbean.finder.ClassFinder;

import javax.interceptor.AroundInvoke;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.apache.openejb.core.transaction.EjbTransactionUtil.afterInvoke;
import static org.apache.openejb.core.transaction.EjbTransactionUtil.createTransactionPolicy;
import static org.apache.openejb.core.transaction.EjbTransactionUtil.handleApplicationException;
import static org.apache.openejb.core.transaction.EjbTransactionUtil.handleSystemException;

/**
 * @org.apache.xbean.XBean element="statelessContainer"
 */
public class StatelessContainer implements org.apache.openejb.RpcContainer {
    private final ConcurrentMap<Class<?>, List<Method>> interceptorCache = new ConcurrentHashMap<Class<?>, List<Method>>();
    private final StatelessInstanceManager instanceManager;
    private final Map<String, BeanContext> deploymentRegistry = new HashMap<String, BeanContext>();
    private final Object containerID;
    private final SecurityService securityService;

    public StatelessContainer(Object id, SecurityService securityService, Duration accessTimeout, Duration closeTimeout, Pool.Builder poolBuilder, int callbackThreads) {
        this.containerID = id;
        this.securityService = securityService;
        this.instanceManager = new StatelessInstanceManager(securityService, accessTimeout, closeTimeout, poolBuilder, callbackThreads);
    }

    @Override
    public synchronized BeanContext[] getBeanContexts() {
        return this.deploymentRegistry.values().toArray(new BeanContext[this.deploymentRegistry.size()]);
    }

    @Override
    public synchronized BeanContext getBeanContext(Object deploymentID) {
        final String id = (String) deploymentID;
        return deploymentRegistry.get(id);
    }

    @Override
    public ContainerType getContainerType() {
        return ContainerType.STATELESS;
    }

    @Override
    public Object getContainerID() {
        return containerID;
    }

    @Override
    public void deploy(BeanContext beanContext) throws OpenEJBException {
        final String id = (String) beanContext.getDeploymentID();
        synchronized (this) {
            deploymentRegistry.put(id, beanContext);
            beanContext.setContainer(this);
        }

        // add it before starting the timer (@PostCostruct)
        if (StatsInterceptor.isStatsActivated()) {
            final StatsInterceptor stats = new StatsInterceptor(beanContext.getBeanClass());
            beanContext.addFirstSystemInterceptor(stats);
        }
    }

    @Override
    public void start(final BeanContext beanContext) throws OpenEJBException {
        this.instanceManager.deploy(beanContext);

        final EjbTimerService timerService = beanContext.getEjbTimerService();
        if (timerService != null) {
            timerService.start();
        }
    }

    @Override
    public void stop(final BeanContext beanContext) throws OpenEJBException {
        beanContext.stop();
    }

    @Override
    public void undeploy(BeanContext beanContext) {
        this.instanceManager.undeploy(beanContext);

        synchronized (this) {
            final String id = (String) beanContext.getDeploymentID();
            beanContext.setContainer(null);
            beanContext.setContainerData(null);
            this.deploymentRegistry.remove(id);
        }
    }

    @Override
    public Object invoke(Object deployID, InterfaceType type, Class callInterface, Method callMethod, Object[] args, Object primKey) throws OpenEJBException {
        final BeanContext beanContext = this.getBeanContext(deployID);

        if (beanContext == null) {
            final String msg = "Deployment does not exist in this container. Deployment(id='" + deployID + "'), Container(id='" + containerID + "')";
            throw new OpenEJBException(msg);
        }

        // Use the backup way to determine call type if null was supplied.
        if (type == null) {
            type = beanContext.getInterfaceType(callInterface);
        }

        final Method runMethod = beanContext.getMatchingBeanMethod(callMethod);
        final ThreadContext callContext = new ThreadContext(beanContext, primKey);
        final ThreadContext oldCallContext = ThreadContext.enter(callContext);
        Instance bean = null;
        CurrentCreationalContext currentCreationalContext = beanContext.get(CurrentCreationalContext.class);
        try {
            final boolean authorized = (type == InterfaceType.TIMEOUT || this.securityService.isCallerAuthorized(callMethod, type));
            if (!authorized) {
                throw new org.apache.openejb.ApplicationException(new javax.ejb.EJBAccessException("Unauthorized Access by Principal Denied"));
            }

            final Class declaringClass = callMethod.getDeclaringClass();
            if (javax.ejb.EJBHome.class.isAssignableFrom(declaringClass) || javax.ejb.EJBLocalHome.class.isAssignableFrom(declaringClass)) {
                if (callMethod.getName().startsWith("create")) {
                    return new ProxyInfo(beanContext, null);
                } else {
                    return null; // EJBHome.remove( ) and other EJBHome methods are not process by the container
                }

            } else if (javax.ejb.EJBObject.class == declaringClass || javax.ejb.EJBLocalObject.class == declaringClass) {
                return null; // EJBObject.remove( ) and other EJBObject methods are not process by the container
            }

            bean = this.instanceManager.getInstance(callContext);

            callContext.setCurrentOperation(type == InterfaceType.TIMEOUT ? Operation.TIMEOUT : Operation.BUSINESS);
            callContext.set(Method.class, runMethod);
            callContext.setInvokedInterface(callInterface);
            if (currentCreationalContext != null) {
                currentCreationalContext.set(bean.creationalContext);
            }
            return _invoke(callMethod, runMethod, args, bean, callContext, type);

        } finally {
            if (bean != null) {
                if (callContext.isDiscardInstance()) {
                    this.instanceManager.discardInstance(callContext, bean);
                } else {
                    this.instanceManager.poolInstance(callContext, bean);
                }
            }
            ThreadContext.exit(oldCallContext);
            if (currentCreationalContext != null) {
                currentCreationalContext.remove();
            }
        }
    }

    private Object _invoke(Method callMethod, Method runMethod, Object[] args, Instance instance, ThreadContext callContext, InterfaceType type)
            throws OpenEJBException {
        final BeanContext beanContext = callContext.getBeanContext();
        final TransactionPolicy txPolicy = createTransactionPolicy(beanContext.getTransactionType(callMethod, type), callContext);

        Object returnValue = null;
        try {
            if (type == InterfaceType.SERVICE_ENDPOINT) {
                callContext.setCurrentOperation(Operation.BUSINESS_WS);
                returnValue = invokeWebService(args, beanContext, runMethod, instance);
            } else {
                final List<InterceptorData> interceptors = beanContext.getMethodInterceptors(runMethod);
                final Operation operation = type == InterfaceType.TIMEOUT ? Operation.TIMEOUT : Operation.BUSINESS;
                final InterceptorStack interceptorStack = new InterceptorStack(instance.bean, runMethod, operation, interceptors, instance.interceptors);
                returnValue = interceptorStack.invoke(args);
            }
        } catch (Throwable re) {// handle reflection exception
            final ExceptionType exceptionType = beanContext.getExceptionType(re);
            if (exceptionType == ExceptionType.SYSTEM) {
                /* System Exception ****************************/

                // The bean instance is not put into the pool via instanceManager.poolInstance
                // and therefore the instance will be garbage collected and destroyed.
                // In case of StrictPooling flag being set to true we also release the semaphore
                // in the discardInstance method of the instanceManager.
                callContext.setDiscardInstance(true);
                handleSystemException(txPolicy, re, callContext);
            } else {
                /* Application Exception ***********************/
                handleApplicationException(txPolicy, re, exceptionType == ExceptionType.APPLICATION_ROLLBACK);
            }
        } finally {
            try {
                afterInvoke(txPolicy, callContext);
            } catch (SystemException e) {
                callContext.setDiscardInstance(true);
                throw e;
            } catch (ApplicationException e) {
                throw e;
            } catch (RuntimeException e) {
                callContext.setDiscardInstance(true);
                throw e;
            }
        }
        return returnValue;
    }

    private Object invokeWebService(Object[] args, BeanContext beanContext, Method runMethod, Instance instance) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("WebService calls must follow format {messageContext, interceptor, [arg...]}.");
        }

        final Object messageContext = args[0];

        // This object will be used as an interceptor in the stack and will be responsible
        // for unmarshalling the soap message parts into an argument list that will be
        // used for the actual method invocation.
        //
        // We just need to make it an interceptor in the OpenEJB sense and tack it on the end
        // of our stack.
        final Object interceptor = args[1];
        final Class<?> interceptorClass = interceptor.getClass();

        //  Add the webservice interceptor to the list of interceptor instances
        final Map<String, Object> interceptors = new HashMap<String, Object>(instance.interceptors);
        interceptors.put(interceptor.getClass().getName(), interceptor);

        //  Create an InterceptorData for the webservice interceptor to the list of interceptorDatas for this method
        final List<InterceptorData> interceptorDatas = new ArrayList<InterceptorData>();
        final InterceptorData providerData = new InterceptorData(interceptorClass);
        providerData.getAroundInvoke().addAll(retrieveAroundInvokes(interceptorClass));
        interceptorDatas.add(0, providerData);
        interceptorDatas.addAll(beanContext.getMethodInterceptors(runMethod));

        final InterceptorStack interceptorStack = new InterceptorStack(instance.bean, runMethod, Operation.BUSINESS_WS, interceptorDatas, interceptors);
        final Object[] params = new Object[runMethod.getParameterTypes().length];
        final ThreadContext threadContext = ThreadContext.getThreadContext();
        Object returnValue = null;
        if (messageContext instanceof javax.xml.rpc.handler.MessageContext) {
            threadContext.set(javax.xml.rpc.handler.MessageContext.class, (javax.xml.rpc.handler.MessageContext) messageContext);
            returnValue = interceptorStack.invoke((javax.xml.rpc.handler.MessageContext) messageContext, params);
        } else if (messageContext instanceof javax.xml.ws.handler.MessageContext) {
            AddressingSupport wsaSupport = NoAddressingSupport.INSTANCE;
            for (int i = 2; i < args.length; i++) {
                if (args[i] instanceof AddressingSupport) {
                    wsaSupport = (AddressingSupport) args[i];
                }
            }
            threadContext.set(AddressingSupport.class, wsaSupport);
            threadContext.set(javax.xml.ws.handler.MessageContext.class, (javax.xml.ws.handler.MessageContext) messageContext);
            returnValue = interceptorStack.invoke((javax.xml.ws.handler.MessageContext) messageContext, params);
        }
        return returnValue;
    }

    private List<Method> retrieveAroundInvokes(Class<?> interceptorClass) {
        final List<Method> cached = this.interceptorCache.get(interceptorClass);
        if (cached != null) {
            return cached;
        }

        final ClassFinder finder = new ClassFinder(interceptorClass);
        List<Method> annotated = finder.findAnnotatedMethods(AroundInvoke.class);
        if (StatelessContainer.class.getClassLoader() == interceptorClass.getClassLoader()) { // use cache only for server classes
            final List<Method> value = new CopyOnWriteArrayList<Method>(annotated);
            annotated = this.interceptorCache.putIfAbsent(interceptorClass, annotated); // ensure it to be thread safe
            if (annotated == null) {
                annotated = value;
            }
        }
        return annotated;
    }
}
