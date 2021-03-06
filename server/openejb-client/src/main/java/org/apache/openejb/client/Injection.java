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
package org.apache.openejb.client;

import java.io.Serializable;

/**
 * @version $Rev: 1511624 $ $Date: 2013-08-08 08:57:43 +0000 (Thu, 08 Aug 2013) $
 */
public class Injection implements Serializable {

    private static final long serialVersionUID = 4009121701163822665L;
    private final String targetClass;
    private final String name;
    private final String jndiName;

    public Injection(final String targetClass, final String name, final String jndiName) {
        if (targetClass == null) {
            throw new NullPointerException("targetClass is null");
        }
        if (name == null) {
            throw new NullPointerException("name is null");
        }
        if (jndiName == null) {
            throw new NullPointerException("jndiName is null");
        }
        this.targetClass = targetClass;
        this.name = name;
        this.jndiName = jndiName;
    }

    public String getJndiName() {
        return jndiName;
    }

    public String getName() {
        return name;
    }

    public String getTargetClass() {
        return targetClass;
    }

    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final Injection injection = (Injection) o;

        return name.equals(injection.name) && targetClass.equals(injection.targetClass);
    }

    public int hashCode() {
        int result;
        result = targetClass.hashCode();
        result = 31 * result + name.hashCode();
        return result;
    }

    public String toString() {
        return targetClass + "." + name + " -> " + jndiName;
    }
}
