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
package org.apache.openejb.test;

import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.ConnectionFactory;
import java.util.Properties;

/**
 * @version $Rev: 1400134 $ $Date: 2012-10-19 15:44:10 +0000 (Fri, 19 Oct 2012) $
 */
public class ActiveMqTestJms implements TestJms {
    public void init(Properties props) {
    }

    public ConnectionFactory getConnectionFactory() {
        return new ActiveMQConnectionFactory("tcp://localhost:61616?jms.watchTopicAdvisories=false\n");
    }
}
