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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.test.entity.cmr.cmrmapping;

import javax.ejb.CreateException;

/**
 * @version $Revision: 607077 $ $Date: 2007-12-27 14:55:23 +0000 (Thu, 27 Dec 2007) $
 */
public abstract class OneOwningSideBean extends AbstractEntityBean {

    // CMP
    public abstract Integer getId();
    public abstract void setId(Integer primaryKey);

    public abstract Integer getField1();
    public abstract void setField1(Integer field1);

    // CMR
    public abstract OneInverseSideLocal getOneInverseSide();
    public abstract void setOneInverseSide(OneInverseSideLocal oneInverseSideLocal);
    
    public Integer ejbCreate(Integer id, Integer field1) throws CreateException {
        setId(id);
        setField1(field1);
        return null;
    }

    public void ejbPostCreate(Integer id, Integer field1) {
    }
}
