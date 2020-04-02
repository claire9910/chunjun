/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dtstack.flinkx.sqlservercdc;

import com.dtstack.flinkx.util.ClassUtil;
import com.dtstack.flinkx.util.ExceptionUtil;
import com.dtstack.flinkx.util.TelnetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Date: 2019/12/03
 * Company: www.dtstack.com
 *
 * @author tudou
 */
public class SqlServerCdcUtil {
    private static final Logger LOG = LoggerFactory.getLogger(SqlServerCdcUtil.class);

    public static final String DRIVER = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    public static Pattern p = Pattern.compile("\\[(.*?)]");

    private static final String STATEMENTS_PLACEHOLDER = "#";
    private static final String CHECK_CDC_DATABASE = "select 1 from sys.databases where name='%s' AND is_cdc_enabled=1";
    private static final String CHECK_CDC_TABLE = "select sys.schemas.name+'.'+sys.tables.name from sys.tables, sys.schemas where sys.tables.is_tracked_by_cdc = 1 and sys.tables.schema_id = sys.schemas.schema_id;";
    private static final String GET_LIST_OF_CDC_ENABLED_TABLES = "EXEC sys.sp_cdc_help_change_data_capture";
    private static final String GET_MAX_LSN = "SELECT sys.fn_cdc_get_max_lsn()";
    private static final String INCREMENT_LSN = "SELECT sys.fn_cdc_increment_lsn(?)";
    private static final String GET_ALL_CHANGES_FOR_TABLE = "SELECT * FROM cdc.[fn_cdc_get_all_changes_#](ISNULL(?,sys.fn_cdc_get_min_lsn('#')), ?, N'all update old')";

    public static void changeDatabase(Connection conn, String databaseName) throws SQLException {
        conn.createStatement().execute(" use " + databaseName);
    }

    public static boolean checkEnabledCdcDatabase(Connection conn, String databaseName) throws SQLException {
        Statement statement = null;
        ResultSet rs = null;
        boolean ret;
        try {
            statement = conn.createStatement();
            rs = statement.executeQuery(String.format(CHECK_CDC_DATABASE, databaseName));
            ret = rs.next();
        } catch (SQLException e) {
            LOG.error("error to query {} Enabled CDC or not, sql = {}, e = {}", databaseName, String.format(CHECK_CDC_DATABASE, databaseName), ExceptionUtil.getErrorMessage(e));
            throw e;
        } finally {
            closeDbResources(rs, statement, null, false);
        }
        return ret;
    }

    public static Set<String> checkUnEnabledCdcTables(Connection conn, Collection<String> tableSet) throws SQLException {
        Statement statement = null;
        ResultSet rs = null;
        CopyOnWriteArraySet<String> unEnabledCdcTables = new CopyOnWriteArraySet<>(tableSet);
        try {
            statement = conn.createStatement();
            rs = statement.executeQuery(CHECK_CDC_TABLE);
            while (rs.next()) {
                String tableName = rs.getString(1);
                unEnabledCdcTables.remove(tableName);
            }
        } catch (SQLException e) {
            LOG.error("error to query UnEnabled CDC Tables, sql = {}, e = {}", CHECK_CDC_TABLE, ExceptionUtil.getErrorMessage(e));
            throw e;
        } finally {
            closeDbResources(rs, statement, null, false);
        }
        return unEnabledCdcTables;
    }

    public static Set<ChangeTable> queryChangeTableSet(Connection conn, String databaseName) throws SQLException {
        Statement statement = null;
        ResultSet rs = null;
        Set<ChangeTable> changeTableSet = new HashSet<>();
        try {
            statement = conn.createStatement();
            rs = statement.executeQuery(GET_LIST_OF_CDC_ENABLED_TABLES);
            while (rs.next()) {
                String column = rs.getString(15);
                Matcher m = p.matcher(column);
                List<String> columnList = new ArrayList<>();
                while(m.find()){
                    columnList.add(m.group(1));
                }
                changeTableSet.add(
                        new ChangeTable(
                                new TableId(databaseName, rs.getString(1), rs.getString(2)),
                                rs.getString(3),
                                rs.getInt(4),
                                Lsn.valueOf(rs.getBytes(6)),
                                Lsn.valueOf(rs.getBytes(7)),
                                columnList
                        )
                );
            }
        } catch (SQLException e) {
            LOG.error("error to query change table set, e = {}", ExceptionUtil.getErrorMessage(e));
            throw e;
        } finally {
            closeDbResources(rs, statement, null, false);
        }
        return changeTableSet;
    }

    public static Lsn getMaxLsn(Connection conn) throws SQLException {
        Statement statement = null;
        ResultSet rs = null;
        Lsn lsn = null;
        try {
            statement = conn.createStatement();
            rs = statement.executeQuery(GET_MAX_LSN);
            rs.next();
            lsn = Lsn.valueOf(rs.getBytes(1));
        } catch (SQLException e) {
            LOG.error("error to query change table set, e = {}", ExceptionUtil.getErrorMessage(e));
            throw e;
        } finally {
            closeDbResources(rs, statement, null, false);
        }
        return lsn;
    }

    public static ChangeTable[] getCdcTablesToQuery(Connection conn, String databaseName, List<String> tableList) throws SQLException {
        Set<ChangeTable> cdcEnabledTableSet = SqlServerCdcUtil.queryChangeTableSet(conn, databaseName);

        if (cdcEnabledTableSet.isEmpty()) {
            LOG.error("No table has enabled CDC or security constraints prevents getting the list of change tables");
        }

        Map<TableId, List<ChangeTable>> whitelistedCdcEnabledTables = cdcEnabledTableSet.stream()
                .filter(changeTable -> {
                    String tableName = changeTable.getSourceTableId().getSchemaName() + "." + changeTable.getSourceTableId().getTableName();
                    return tableList.contains(tableName);
                })
                .collect(Collectors.groupingBy(ChangeTable::getSourceTableId));

        List<ChangeTable> changeTableList = new ArrayList<>();
        for (List<ChangeTable> captures : whitelistedCdcEnabledTables.values()) {
            ChangeTable currentTable = captures.get(0);
            if (captures.size() > 1) {
                ChangeTable futureTable;
                if (captures.get(0).getStartLsn().compareTo(captures.get(1).getStartLsn()) < 0) {
                    futureTable = captures.get(1);
                } else {
                    currentTable = captures.get(1);
                    futureTable = captures.get(0);
                }
                currentTable.setStopLsn(futureTable.getStartLsn());
                changeTableList.add(futureTable);
                LOG.info("Multiple capture instances present for the same table: {} and {}", currentTable, futureTable);
            }
            changeTableList.add(currentTable);
        }

        return changeTableList.toArray(new ChangeTable[0]);
    }

    public static Lsn incrementLsn(Connection conn, Lsn lsn) throws SQLException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        Lsn ret;
        try {
            ps = conn.prepareStatement(INCREMENT_LSN);
            ps.setBytes(1, lsn.getBinary());
            rs = ps.executeQuery();
            rs.next();
            ret = Lsn.valueOf(rs.getBytes(1));
        } catch (SQLException e) {
            LOG.error("error to query increment lsn, e = {}", ExceptionUtil.getErrorMessage(e));
            throw e;
        } finally {
            closeDbResources(rs, ps, null, false);
        }
        return ret;
    }

    public static ResultSet[] getChangesForTables(Connection conn, ChangeTable[] changeTables, Lsn intervalFromLsn, Lsn intervalToLsn) throws SQLException {
        ResultSet[] resultSets = new ResultSet[changeTables.length];
        String sql;
        int idx = 0;
        try {
            for (ChangeTable changeTable : changeTables) {
                sql = GET_ALL_CHANGES_FOR_TABLE.replace(STATEMENTS_PLACEHOLDER, changeTable.getCaptureInstance());
                Lsn fromLsn = changeTable.getStartLsn().compareTo(intervalFromLsn) > 0 ? changeTable.getStartLsn() : intervalFromLsn;

                //notice : statement is not closed, there maybe have problem.
                PreparedStatement statement = conn.prepareStatement(sql);
                statement.setBytes(1, fromLsn.getBinary());
                statement.setBytes(2, intervalToLsn.getBinary());
                ResultSet rs = statement.executeQuery();
                resultSets[idx] = rs;
                idx++;
            }
        } catch (Exception e) {
            LOG.error("error to getChangesForTables, e = {}", ExceptionUtil.getErrorMessage(e));
            throw e;
        }
        return resultSets;
    }

    /**
     * clob转string
     * @param obj   clob
     * @return
     * @throws Exception
     */
    public static Object clobToString(Object obj) throws Exception{
        if(obj instanceof Clob){
            Clob clob = (Clob)obj;
            BufferedReader bf = new BufferedReader(clob.getCharacterStream());
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = bf.readLine()) != null){
                stringBuilder.append(line);
            }
            bf.close();
            return stringBuilder.toString();
        } else {
            return obj;
        }
    }

    /**
     * 获取jdbc连接(超时10S)
     * @param url       url
     * @param username  账号
     * @param password  密码
     * @return
     * @throws SQLException
     */
    public static Connection getConnection(String url, String username, String password) throws SQLException {
        Connection dbConn;
        synchronized (ClassUtil.lock_str){
            DriverManager.setLoginTimeout(10);

            // telnet
            TelnetUtil.telnet(url);

            if (username == null) {
                dbConn = DriverManager.getConnection(url);
            } else {
                dbConn = DriverManager.getConnection(url, username, password);
            }
        }

        return dbConn;
    }


    /**
     * 关闭连接资源
     * @param rs        ResultSet
     * @param stmt      Statement
     * @param conn      Connection
     * @param commit
     */
    public static void closeDbResources(ResultSet rs, Statement stmt, Connection conn, boolean commit) {
        if (null != rs) {
            try {
                rs.close();
            } catch (SQLException e) {
                LOG.warn("Close resultSet error: {}", ExceptionUtil.getErrorMessage(e));
            }
        }

        if (null != stmt) {
            try {
                stmt.close();
            } catch (SQLException e) {
                LOG.warn("Close statement error:{}", ExceptionUtil.getErrorMessage(e));
            }
        }

        if (null != conn) {
            try {
                if(commit && !conn.isClosed()){
                    conn.commit();
                }

                conn.close();
            } catch (SQLException e) {
                LOG.warn("Close connection error:{}", ExceptionUtil.getErrorMessage(e));
            }
        }
    }
}