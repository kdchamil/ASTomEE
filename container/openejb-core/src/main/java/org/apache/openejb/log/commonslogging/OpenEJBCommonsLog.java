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
package org.apache.openejb.log.commonslogging;

import org.apache.commons.logging.Log;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class OpenEJBCommonsLog implements Log, Serializable {
    private transient Logger logger;
    private String category;

    public OpenEJBCommonsLog(final String category) {
        this.category = category;
        logger = Logger.getInstance(LogCategory.OPENEJB, category);
    }

    @Override
    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    @Override
    public boolean isErrorEnabled() {
        return logger.isErrorEnabled();
    }

    @Override
    public boolean isFatalEnabled() {
        return logger.isFatalEnabled();
    }

    @Override
    public boolean isInfoEnabled() {
        return logger.isInfoEnabled();
    }

    @Override
    public boolean isTraceEnabled() {
        return logger.isDebugEnabled();
    }

    @Override
    public boolean isWarnEnabled() {
        return logger.isWarningEnabled();
    }

    @Override
    public void trace(Object message) {
        logger.debug(message + "");
    }

    @Override
    public void trace(Object message, Throwable t) {
        logger.debug(message + "", t);
    }

    @Override
    public void debug(Object message) {
        logger.debug(message + "");
    }

    @Override
    public void debug(Object message, Throwable t) {
        logger.debug(message + "", t);
    }

    @Override
    public void info(Object message) {
        logger.info(message + "");
    }

    @Override
    public void info(Object message, Throwable t) {
        logger.info(message + "", t);
    }

    @Override
    public void warn(Object message) {
        logger.warning(message + "");
    }

    @Override
    public void warn(Object message, Throwable t) {
        logger.warning(message + "", t);
    }

    @Override
    public void error(Object message) {
        logger.error(message + "");
    }

    @Override
    public void error(Object message, Throwable t) {
        logger.error(message + "", t);
    }

    @Override
    public void fatal(Object message) {
        logger.fatal(message + "");
    }

    @Override
    public void fatal(Object message, Throwable t) {
        logger.fatal(message + "", t);
    }

    private void writeObject(final ObjectOutputStream out) throws IOException {
        out.writeUTF(category);
    }

    private void readObject(final ObjectInputStream in) throws IOException {
        logger = Logger.getInstance(LogCategory.OPENEJB, in.readUTF());
    }
}
