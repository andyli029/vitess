package com.flipkart.vitess.jdbc;

import com.flipkart.vitess.util.Constants;
import com.flipkart.vitess.util.StringUtils;
import com.youtube.vitess.client.Context;
import com.youtube.vitess.client.VTGateConn;
import com.youtube.vitess.client.VTGateTx;
import com.youtube.vitess.client.cursor.Cursor;
import com.youtube.vitess.proto.Query;
import com.youtube.vitess.proto.Topodata;

import java.sql.*;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Created by harshit.gangal on 19/01/16.
 */
public class VitessStatement implements Statement {

    /* Get actual class name to be printed on */
    private static Logger logger = Logger.getLogger(VitessStatement.class.getName());
    protected VitessResultSet vitessResultSet;
    protected VitessConnection vitessConnection;
    protected boolean closed;
    protected long resultCount;
    protected long queryTimeoutInMillis = Constants.DEFAULT_TIMEOUT;
    protected int maxFieldSize = Constants.MAX_BUFFER_SIZE;
    protected int maxRows = 0;
    protected int fetchSize = 0;
    protected int resultSetConcurrency;
    protected int resultSetType;
    protected boolean retrieveGeneratedKeys = false;
    protected long generatedId = -1;


    public VitessStatement(VitessConnection vitessConnection) {
        this(vitessConnection, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    public VitessStatement(VitessConnection vitessConnection, int resultSetType,
                           int resultSetConcurrency) {
        this.vitessConnection = vitessConnection;
        this.vitessResultSet = null;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.closed = false;
        this.resultCount = -1;
        this.vitessConnection.registerStatement(this);
    }

    /**
     * To execute an Select/Show Statement
     *
     * @param sql - SQL Query
     * @return ResultSet
     * @throws SQLException
     */
    public ResultSet executeQuery(String sql) throws SQLException {
        VTGateConn vtGateConn;
        Topodata.TabletType tabletType;
        Cursor cursor;
        boolean showSql;

        checkOpen();
        checkSQLNullOrEmpty(sql);
        closeOpenResultSetAndResetCount();

        if (this instanceof VitessPreparedStatement) { //PreparedStatement cannot call this method
            throw new SQLException(Constants.SQLExceptionMessages.METHOD_NOT_ALLOWED);
        }

        //Setting to default value
        this.retrieveGeneratedKeys = false;
        this.generatedId = -1;

        vtGateConn = this.vitessConnection.getVtGateConn();
        tabletType = this.vitessConnection.getTabletType();

        showSql = StringUtils.startsWithIgnoreCaseAndWs(sql, Constants.SQL_SHOW);
        if (showSql) {
            String keyspace = this.vitessConnection.getKeyspace();
            List<byte[]> keyspaceIds = Arrays.asList(new byte[] {1}); //To Hit any single shard
            Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);

            cursor =
                vtGateConn.executeKeyspaceIds(context, sql, keyspace, keyspaceIds, null, tabletType)
                    .checkedGet();
        } else {
            if (tabletType != Topodata.TabletType.MASTER || this.vitessConnection.getAutoCommit()) {
                Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
                if (Constants.QueryExecuteType.SIMPLE == vitessConnection.getExecuteTypeParam()) {
                    cursor = vtGateConn.execute(context, sql, null, tabletType).checkedGet();
                } else {
                    cursor = vtGateConn.streamExecute(context, sql, null, tabletType);
                }
            } else {
                VTGateTx vtGateTx = this.vitessConnection.getVtGateTx();
                if (null == vtGateTx) {
                    Context context =
                        this.vitessConnection.createContext(this.queryTimeoutInMillis);
                    vtGateTx = vtGateConn.begin(context).checkedGet();
                    this.vitessConnection.setVtGateTx(vtGateTx);
                }
                Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
                /* Stream query is not suppose to run in a txn. */
                cursor = vtGateTx.execute(context, sql, null, tabletType).checkedGet();
            }
        }

        if (null == cursor) {
            throw new SQLException(Constants.SQLExceptionMessages.METHOD_CALL_FAILED);
        }

        this.vitessResultSet = new VitessResultSet(cursor, this);
        return (this.vitessResultSet);
    }

    /**
     * To execute a DML statement
     *
     * @param sql - SQL Query
     * @return Rows Affected Count
     * @throws SQLException
     */
    public int executeUpdate(String sql) throws SQLException {
        return executeUpdate(sql, Statement.NO_GENERATED_KEYS);
    }

    /**
     * To Execute Unknown Statement
     *
     * @param sql
     * @return
     * @throws SQLException
     */
    public boolean execute(String sql) throws SQLException {
        return execute(sql, Statement.NO_GENERATED_KEYS);
    }

    /**
     * To get the resultSet generated
     *
     * @return ResultSet
     * @throws SQLException
     */
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return this.vitessResultSet;
    }

    public int getUpdateCount() throws SQLException {
        int truncatedUpdateCount;

        checkOpen();

        if (null != this.vitessResultSet) {
            return -1;
        }

        if (this.resultCount > Integer.MAX_VALUE) {
            truncatedUpdateCount = Integer.MAX_VALUE;
        } else {
            truncatedUpdateCount = (int) this.resultCount;
        }
        return truncatedUpdateCount;
    }

    public void close() throws SQLException {
        SQLException postponedSQLException = null;

        if (!this.closed) {
            if (null != this.vitessConnection) {
                this.vitessConnection.unregisterStatement(this);
            }
            try {
                if (null != this.vitessResultSet) {
                    this.vitessResultSet.close();
                }
            } catch (SQLException ex) {
                postponedSQLException = ex;
            }

            this.vitessConnection = null;
            this.vitessResultSet = null;
            this.closed = true;

            if (null != postponedSQLException) {
                throw postponedSQLException;
            }
        }
    }

    public int getMaxFieldSize() throws SQLException {
        checkOpen();
        return this.maxFieldSize;
    }

    public void setMaxFieldSize(int max) throws SQLException {
        /* Currently not used
        checkOpen();
        if (max < 0 || max > Constants.MAX_BUFFER_SIZE) {
            throw new SQLException(
                Constants.SQLExceptionMessages.ILLEGAL_VALUE_FOR + "max field size");
        }
        this.maxFieldSize = max;
        */
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public int getMaxRows() throws SQLException {
        checkOpen();
        return this.maxRows;
    }

    public void setMaxRows(int max) throws SQLException {
        checkOpen();
        if (max < 0) {
            throw new SQLException(Constants.SQLExceptionMessages.ILLEGAL_VALUE_FOR + "max row");
        }
        this.maxRows = max;
    }

    public int getQueryTimeout() throws SQLException {
        checkOpen();
        return (int) (this.queryTimeoutInMillis / 1000);
    }

    public void setQueryTimeout(int seconds) throws SQLException {
        checkOpen();
        if (seconds < 0) {
            throw new SQLException(
                Constants.SQLExceptionMessages.ILLEGAL_VALUE_FOR + "query timeout");
        }
        this.queryTimeoutInMillis = (long) seconds * 1000;
    }

    /**
     * Return Warnings
     * <p/>
     * Not implementing as Error is Thrown when occurred
     *
     * @return SQLWarning or null
     * @throws SQLException
     */
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return null;
    }

    /**
     * Clear the warnings - Not saving Warnings
     *
     * @throws SQLException
     */
    public void clearWarnings() throws SQLException {
        //no-op
    }

    public void setCursorName(String name) throws SQLException {
        checkOpen();
    }

    public int getFetchDirection() throws SQLException {
        checkOpen();
        return ResultSet.FETCH_FORWARD;
    }

    public void setFetchDirection(int direction) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public int getFetchSize() throws SQLException {
        checkOpen();
        return this.fetchSize;
    }

    public void setFetchSize(int rows) throws SQLException {
        /* Currently not used
        checkOpen();
        if (rows < 0) {
            throw new SQLException(Constants.SQLExceptionMessages.ILLEGAL_VALUE_FOR + "fetch size");
        }
        this.fetchSize = rows;
        */
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public int getResultSetConcurrency() throws SQLException {
        checkOpen();
        return this.resultSetConcurrency;
    }

    public int getResultSetType() throws SQLException {
        checkOpen();
        return this.resultSetType;
    }

    public Connection getConnection() throws SQLException {
        checkOpen();
        return vitessConnection;
    }

    public boolean isClosed() throws SQLException {
        return this.closed;
    }

    /**
     * Unwrap a class
     *
     * @param iface - A Class defining an interface that the result must implement.
     * @param <T>   - the type of the class modeled by this Class object
     * @return an object that implements the interface. May be a proxy for the actual implementing object.
     * @throws SQLException
     */
    public <T> T unwrap(Class<T> iface) throws SQLException {
        try {
            return iface.cast(this);
        } catch (ClassCastException cce) {
            throw new SQLException(
                Constants.SQLExceptionMessages.CLASS_CAST_EXCEPTION + iface.toString(), cce);
        }
    }

    /**
     * Checking Wrapper
     *
     * @param iface - A Class defining an interface that the result must implement.
     * @return true if this implements the interface or directly or indirectly wraps an object that does.
     * @throws SQLException
     */
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        checkOpen();
        return iface.isInstance(this);
    }

    /**
     * @return
     * @throws SQLException
     */
    public ResultSet getGeneratedKeys() throws SQLException {
        checkOpen();
        if (!this.retrieveGeneratedKeys) {
            throw new SQLException(Constants.SQLExceptionMessages.GENERATED_KEYS_NOT_REQUESTED);
        }

        String[] columnNames = new String[1];
        columnNames[0] = "GENERATED_KEY";
        Query.Type[] columnTypes = new Query.Type[1];
        columnTypes[0] = Query.Type.UINT64;
        String[][] data = null;

        if (this.generatedId > 0) {
            data = new String[1][1];
            data[0][0] = String.valueOf(this.generatedId);
        }

        return new VitessResultSet(columnNames, columnTypes, data);
    }

    /**
     * To execute DML statement
     *
     * @param sql               - SQL Query
     * @param autoGeneratedKeys - Flag for generated Keys
     * @return Row Affected Count
     * @throws SQLException
     */
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        VTGateConn vtGateConn;
        Topodata.TabletType tabletType;
        VTGateTx vtGateTx;
        Cursor cursor;
        int truncatedUpdateCount;

        checkOpen();
        checkSQLNullOrEmpty(sql);
        closeOpenResultSetAndResetCount();

        if (this instanceof VitessPreparedStatement) { //PreparedStatement cannot call this method
            throw new SQLException(Constants.SQLExceptionMessages.METHOD_NOT_ALLOWED);
        }

        vtGateConn = this.vitessConnection.getVtGateConn();
        tabletType = this.vitessConnection.getTabletType();

        if (tabletType != Topodata.TabletType.MASTER) {
            throw new SQLException(Constants.SQLExceptionMessages.DML_NOT_ON_MASTER);
        }

        vtGateTx = this.vitessConnection.getVtGateTx();
        if (null == vtGateTx) {
            Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
            vtGateTx = vtGateConn.begin(context).checkedGet();
            this.vitessConnection.setVtGateTx(vtGateTx);
        }

        Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
        cursor = vtGateTx.execute(context, sql, null, tabletType).checkedGet();

        if (this.vitessConnection.getAutoCommit()) {
            context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
            vtGateTx.commit(context).checkedGet();
            this.vitessConnection.setVtGateTx(null);
        }

        if (null == cursor) {
            throw new SQLException(Constants.SQLExceptionMessages.METHOD_CALL_FAILED);
        }

        if (!(null == cursor.getFields() || cursor.getFields().isEmpty())) {
            throw new SQLException(Constants.SQLExceptionMessages.SQL_RETURNED_RESULT_SET);
        }

        if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS) {
            this.retrieveGeneratedKeys = true;
            this.generatedId = cursor.getInsertId();
        } else {
            this.retrieveGeneratedKeys = false;
            this.generatedId = -1;
        }

        this.resultCount = cursor.getRowsAffected();

        if (this.resultCount > Integer.MAX_VALUE) {
            truncatedUpdateCount = Integer.MAX_VALUE;
        } else {
            truncatedUpdateCount = (int) this.resultCount;
        }

        return truncatedUpdateCount;
    }

    /**
     * To execute Unknown Statement
     *
     * @param sql               - SQL Query
     * @param autoGeneratedKeys - Flag for generated Keys
     * @return - <code>true</code> if the first result is a <code>ResultSet</code>
     * object; <code>false</code> if it is an update count or there are
     * no results
     * @throws SQLException
     */
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        VTGateConn vtGateConn;
        Topodata.TabletType tabletType;
        Cursor cursor;
        boolean selectSql;
        boolean showSql;

        checkOpen();
        checkSQLNullOrEmpty(sql);
        closeOpenResultSetAndResetCount();

        if (this instanceof VitessPreparedStatement) { //PreparedStatement cannot call this method
            throw new SQLException(Constants.SQLExceptionMessages.METHOD_NOT_ALLOWED);
        }

        vtGateConn = this.vitessConnection.getVtGateConn();
        tabletType = this.vitessConnection.getTabletType();

        selectSql = StringUtils.startsWithIgnoreCaseAndWs(sql, Constants.SQL_SELECT);
        showSql = StringUtils.startsWithIgnoreCaseAndWs(sql, Constants.SQL_SHOW);

        if (showSql) {
            String keyspace = this.vitessConnection.getKeyspace();
            List<byte[]> keyspaceIds = Arrays.asList(new byte[] {1}); //To Hit any single shard

            Context context = this.vitessConnection.createContext(this.queryTimeoutInMillis);
            cursor =
                vtGateConn.executeKeyspaceIds(context, sql, keyspace, keyspaceIds, null, tabletType)
                    .checkedGet();
            if (!(null == cursor || null == cursor.getFields() || cursor.getFields().isEmpty())) {
                this.vitessResultSet = new VitessResultSet(cursor, this);
                return true;
            } else {
                throw new SQLException(Constants.SQLExceptionMessages.METHOD_CALL_FAILED);
            }

        } else if (selectSql) {
            this.executeQuery(sql);
            return true;
        } else {
            this.executeUpdate(sql, autoGeneratedKeys);
            return false;
        }
    }

    // Internal Methods Created


    protected void closeOpenResultSetAndResetCount() throws SQLException {
        try {
            if (null != this.vitessResultSet) {
                this.vitessResultSet.close();
            }
        } catch (SQLException ex) {
            throw new SQLException(ex);
        } finally {
            this.vitessResultSet = null;
            this.resultCount = -1;
        }
    }

    protected void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException(Constants.SQLExceptionMessages.STMT_CLOSED);
        }
    }

    protected void checkSQLNullOrEmpty(String sql) throws SQLException {
        if (StringUtils.isNullOrEmptyWithoutWS(sql)) {
            throw new SQLException(Constants.SQLExceptionMessages.SQL_EMPTY);
        }
    }

    //Unsupported Methods

    public void addBatch(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void clearBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public int[] executeBatch() throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    /**
     * Not Required to be implemented as More Results are handled in next() call
     *
     * @throws SQLException
     */
    public boolean getMoreResults() throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    /**
     * Not Required to be implemented as More Results are handled in next() call
     *
     * @throws SQLException
     */
    public boolean getMoreResults(int current) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setEscapeProcessing(boolean enable) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void cancel() throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public boolean execute(String sql, String[] columnNames) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public int getResultSetHoldability() throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public boolean isPoolable() throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void setPoolable(boolean poolable) throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public void closeOnCompletion() throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

    public boolean isCloseOnCompletion() throws SQLException {
        throw new SQLFeatureNotSupportedException(
            Constants.SQLExceptionMessages.SQL_FEATURE_NOT_SUPPORTED);
    }

}
