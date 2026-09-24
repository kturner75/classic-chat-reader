package com.classicchatreader.transfer;

import java.sql.Connection;

/** Runs a production transfer's reads and writes in one SERIALIZABLE transaction on a dedicated connection. */
public final class SerializableTransaction {
    private SerializableTransaction() {}

    @FunctionalInterface public interface Work<T> { T run() throws Exception; }

    public static <T> T run(Connection c, Work<T> work) throws Exception {
        if (!c.getAutoCommit()) throw new IllegalArgumentException("A dedicated connection is required");
        int isolation = c.getTransactionIsolation();
        c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        c.setAutoCommit(false);
        try {
            T result = work.run();
            c.commit();
            return result;
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
            c.setTransactionIsolation(isolation);
        }
    }
}
