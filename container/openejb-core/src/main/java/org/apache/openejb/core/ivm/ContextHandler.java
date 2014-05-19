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
package org.apache.openejb.core.ivm;

import org.apache.openejb.core.ivm.naming.ContextWrapper;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.spi.ContainerSystem;

import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

public class ContextHandler extends ContextWrapper {
    public ContextHandler(final Context jndiContext) {
        super(jndiContext);
    }

    @Override
    public Object lookup(final Name name) throws NamingException {
        try {
            return context.lookup(name);
        } catch (NameNotFoundException nnfe) {
            try {
                return SystemInstance.get().getComponent(ContainerSystem.class).getJNDIContext().lookup(name);
            } catch (NameNotFoundException nnfe2) {
                // ignore, let it be thrown
            }
            throw nnfe;
        }
    }

    @Override
    public Object lookup(String name) throws NamingException {
        try {
            return context.lookup(name);
        } catch (NameNotFoundException nnfe) {
            try {
                return SystemInstance.get().getComponent(ContainerSystem.class).getJNDIContext().lookup(name);
            } catch (NameNotFoundException nnfe2) {
                // ignore, let it be thrown
            }
            throw nnfe;
        }
    }
}
