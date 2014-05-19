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
package org.apache.openejb.resource.jdbc.managed.local;

import org.apache.openejb.OpenEJB;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;

import javax.sql.CommonDataSource;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import javax.transaction.xa.XAResource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ManagedConnection implements InvocationHandler {
    private static final Logger LOGGER = Logger.getInstance(LogCategory.OPENEJB.OPENEJB_RESOURCE_JDBC,ManagedConnection.class);

    private static final Map<Integer, Map<Transaction, Connection>> CONNECTION_BY_TX_BY_DS = new ConcurrentHashMap<Integer, Map<Transaction, Connection>>();

    private final TransactionManager transactionManager;
    private final LocalXAResource xaResource;
    protected Connection delegate;
    private Transaction currentTransaction;
    private boolean closed;

    private final Map<Transaction, Connection> connectionByTx;

    public ManagedConnection(final CommonDataSource ds, final Connection connection, final TransactionManager txMgr) {
        delegate = connection;
        transactionManager = txMgr;
        closed = false;
        xaResource = new LocalXAResource(delegate);
        connectionByTx = CONNECTION_BY_TX_BY_DS.get(ds.hashCode());
    }

    public XAResource getXAResource() throws SQLException {
        return xaResource;
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
        // first some Object method management
        final String mtdName = method.getName();
        if ("toString".equals(mtdName)) {
            return "ManagedConnection{" + delegate + "}";
        }
        if ("hashCode".equals(mtdName)) {
            return delegate.hashCode();
        }
        if ("equals".equals(mtdName)) {
            return delegate.equals(args[0]);
        }

        // here the real logic starts
        try {
            final Transaction transaction = transactionManager.getTransaction();

            if (transaction == null) { // shouldn't be possible
                return invoke(method, delegate, args);
            }

            // if we have a tx check it is the same this connection is linked to
            if (currentTransaction != null) {
                if (isUnderTransaction(currentTransaction.getStatus())) {
                    if (!currentTransaction.equals(transaction)) {
                        throw new SQLException("Connection can not be used while enlisted in another transaction");
                    }
                    return invokeUnderTransaction(delegate, method, args);
                } else {
                    close(delegate);
                }
            }

            // get the already bound connection to the current transaction
            // or enlist this one in the tx
            int status = transaction.getStatus();
            if (isUnderTransaction(status)) {
                final Connection connection = connectionByTx.get(transaction);
                if (connection != delegate) {
                    if (connection != null) { // use already existing one
                        delegate.close(); // return to pool
                        delegate = connection;
                    } else {
                        connectionByTx.put(transaction, delegate);
                        currentTransaction = transaction;
                        try {
                            transaction.enlistResource(getXAResource());
                        } catch (RollbackException ignored) {
                            // no-op
                        } catch (SystemException e) {
                            throw new SQLException("Unable to enlist connection the transaction", e);
                        }

                        transaction.registerSynchronization(new ClosingSynchronization(delegate, connectionByTx));

                        try {
                            delegate.setAutoCommit(false);
                        } catch (final SQLException xae) { // we are alreay in a transaction so this can't be called from a user perspective - some XA DataSource prevents it in their code
                            final String message = "Can't set auto commit to false cause the XA datasource doesn't support it, this is likely an issue";
                            if (LOGGER.isDebugEnabled()) { // we don't want to print the exception by default
                                LOGGER.warning(message, xae);
                            } else {
                                LOGGER.warning(message);
                            }
                        }
                    }
                }

                return invokeUnderTransaction(delegate, method, args);
            }

            return invoke(method, delegate, args);
        } catch (InvocationTargetException ite) {
            throw ite.getTargetException();
        }
    }

    private static Object invoke(final Method method, final Connection delegate, final Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException ite) {
            throw ite.getCause();
        }
    }

    private Object invokeUnderTransaction(final Connection delegate, final Method method, final Object[] args) throws Exception {
        final String mtdName = method.getName();
        if ("setAutoCommit".equals(mtdName)
                || "commit".equals(mtdName)
                || "rollback".equals(mtdName)
                || "setSavepoint".equals(mtdName)
                || "setReadOnly".equals(mtdName)) {
            throw forbiddenCall(mtdName);
        }
        if ("close".equals(mtdName)) {
            return close();
        }
        if ("isClosed".equals(mtdName) && closed) {
            return true; // if !closed let's delegate to the underlying connection
        }
        return method.invoke(delegate, args);
    }

    // will be done later
    // we need to delay it in case of rollback
    private Object close() {
        closed = true;
        return null;
    }

    private static boolean isUnderTransaction(int status) {
        return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK;
    }

    private static SQLException forbiddenCall(final String mtdName) {
        return new SQLException("can't call " + mtdName + " when the connection is JtaManaged");
    }

    private static void close(final Connection connection) {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            // no-op
        }
    }

    public static void pushDataSource(final CommonDataSource ds) {
        CONNECTION_BY_TX_BY_DS.put(ds.hashCode(), new ConcurrentHashMap<Transaction, Connection>());
    }

    public static void cleanDataSource(final CommonDataSource ds) {
        final Map<Transaction, Connection> map = CONNECTION_BY_TX_BY_DS.remove(ds.hashCode());
        if (map != null) {
            map.clear();
        }
    }

    private static class ClosingSynchronization implements Synchronization {
        private final Connection connection;
        private final Map<Transaction, Connection> mapToCleanup;

        public ClosingSynchronization(final Connection delegate, Map<Transaction, Connection> connByTx) {
            connection = delegate;
            mapToCleanup = connByTx;
        }

        @Override
        public void beforeCompletion() {
            // no-op
        }

        @Override
        public void afterCompletion(int status) {
            close(connection);
            try {
                final Transaction tx = OpenEJB.getTransactionManager().getTransaction();
                mapToCleanup.remove(tx);
            } catch (SystemException ignored) {
                // no-op
            }
        }
    }
}
