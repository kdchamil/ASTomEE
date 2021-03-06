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

/**
 * @version $Rev: 1511624 $ $Date: 2013-08-08 08:57:43 +0000 (Thu, 08 Aug 2013) $
 */
public class DefaultedThreadLocal<T> extends ThreadLocal<T> {

    private final T defaultValue;

    public DefaultedThreadLocal(final T defaultValue) {
        this.defaultValue = defaultValue;
    }

    @Override
    public T get() {
        final T value = super.get();
        return (value == null) ? defaultValue : value;
    }
}
