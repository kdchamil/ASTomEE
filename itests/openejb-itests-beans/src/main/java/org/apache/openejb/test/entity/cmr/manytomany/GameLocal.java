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
package org.apache.openejb.test.entity.cmr.manytomany;

import java.util.Set;
import javax.ejb.EJBLocalObject;

/**
 * @version $Revision: 607077 $ $Date: 2007-12-27 14:55:23 +0000 (Thu, 27 Dec 2007) $
 */
public interface GameLocal extends EJBLocalObject {
    // CMP
    public Integer getId();
    public void setId(Integer id);

    public String getName();
    public void setName(String name);

    public Integer getRating();
    public void setRating(Integer rating);

    // CMR
    public Set<PlatformLocal> getPlatforms();
    public void setPlatforms(Set<PlatformLocal> platforms);
}
