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
package org.apache.openejb.interceptors;

import javax.interceptor.AroundInvoke;
import javax.interceptor.InvocationContext;

/**
 * @version $Rev: 1086191 $ $Date: 2011-03-28 11:27:30 +0000 (Mon, 28 Mar 2011) $
 */
public class MethodLevelInterceptorTwo {

    @AroundInvoke
    protected Object businessMethodInterceptor(InvocationContext ic) throws Exception {
        return Utils.addClassSimpleName(ic, this.getClass().getSimpleName());
    }
}
