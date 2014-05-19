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
package org.apache.openejb.core.interceptor;

import org.apache.openejb.core.Operation;
import org.apache.openejb.core.ThreadContext;
import org.apache.openejb.util.proxy.DynamicProxyImplFactory;

import javax.interceptor.InvocationContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @version $Rev: 1462598 $ $Date: 2013-03-29 18:42:09 +0000 (Fri, 29 Mar 2013) $
 */
public class InterceptorStack {
    private final Object beanInstance;
    private final List<Interceptor> interceptors;
    private final Method targetMethod;
    private final Operation operation;

    public InterceptorStack(Object beanInstance, Method targetMethod, Operation operation, List<InterceptorData> interceptorDatas, Map<String, Object> interceptorInstances) {
        if (interceptorDatas == null) throw new NullPointerException("interceptorDatas is null");
        if (interceptorInstances == null) throw new NullPointerException("interceptorInstances is null");
        this.beanInstance = beanInstance;
        this.targetMethod = targetMethod;
        this.operation = operation;

        interceptors = new ArrayList<Interceptor>(interceptorDatas.size());

        for (InterceptorData interceptorData : interceptorDatas) {
            Class interceptorClass = interceptorData.getInterceptorClass();
            Object interceptorInstance = interceptorInstances.get(interceptorClass.getName());
            if (interceptorInstance == null) {
                throw new IllegalArgumentException("No interceptor of type " + interceptorClass.getName());
            }

            Set<Method> methods = interceptorData.getMethods(operation);
            for (Method method : methods) {
                final Interceptor interceptor;
                Object handler = DynamicProxyImplFactory.realHandler(interceptorInstance);
                if (handler != null && method.getDeclaringClass().equals(handler.getClass())) { // dynamic impl
                    interceptor = new Interceptor(handler, method);
                } else {
                    interceptor = new Interceptor(interceptorInstance, method);
                }
                interceptors.add(interceptor);
            }
        }

    }

    public InvocationContext createInvocationContext(Object... parameters) {
        return new ReflectionInvocationContext(operation, interceptors, beanInstance, targetMethod, parameters);
    }

    public Object invoke(Object... parameters) throws Exception {
        try {
            InvocationContext invocationContext = createInvocationContext(parameters);
            if (ThreadContext.getThreadContext() != null) {
                ThreadContext.getThreadContext().set(InvocationContext.class, invocationContext);
            }
            return invocationContext.proceed();
        } finally {
            if (ThreadContext.getThreadContext() != null) {
                ThreadContext.getThreadContext().remove(InvocationContext.class);
            }
        }
    }

    public Object invoke(javax.xml.ws.handler.MessageContext messageContext, Object... parameters) throws Exception {
        try {
            InvocationContext invocationContext = new JaxWsInvocationContext(operation, interceptors, beanInstance, targetMethod, messageContext, parameters);
            ThreadContext.getThreadContext().set(InvocationContext.class, invocationContext);
            return invocationContext.proceed();
        } finally {
            ThreadContext.getThreadContext().remove(InvocationContext.class);
        }
    }

    public Object invoke(javax.xml.rpc.handler.MessageContext messageContext, Object... parameters) throws Exception {
        try {
            InvocationContext invocationContext = new JaxRpcInvocationContext(operation, interceptors, beanInstance, targetMethod, messageContext, parameters);
            ThreadContext.getThreadContext().set(InvocationContext.class, invocationContext);
            return invocationContext.proceed();
        } finally {
            ThreadContext.getThreadContext().remove(InvocationContext.class);
        }
    }
}
