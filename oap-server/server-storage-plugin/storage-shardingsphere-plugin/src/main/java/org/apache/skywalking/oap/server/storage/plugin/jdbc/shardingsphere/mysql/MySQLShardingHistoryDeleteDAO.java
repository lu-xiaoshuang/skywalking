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
 *
 */

package org.apache.skywalking.oap.server.storage.plugin.jdbc.shardingsphere.mysql;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.analysis.DownSampling;
import org.apache.skywalking.oap.server.core.storage.StorageException;
import org.apache.skywalking.oap.server.core.storage.model.Model;
import org.apache.skywalking.oap.server.library.client.jdbc.JDBCClientException;
import org.apache.skywalking.oap.server.library.client.jdbc.hikaricp.JDBCHikariCPClient;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.storage.plugin.jdbc.h2.dao.H2HistoryDeleteDAO;
import org.joda.time.DateTime;

@Slf4j
public class MySQLShardingHistoryDeleteDAO extends H2HistoryDeleteDAO {

    private final JDBCHikariCPClient client;
    private final MySQLShardingStorageConfig config;
    private final ModuleManager manager;
    private final Set<String> dataSources;
    private final Map<String, Long> tableLatestSuccess;

    public MySQLShardingHistoryDeleteDAO(JDBCHikariCPClient client, MySQLShardingStorageConfig config,
                                         ModuleManager manager) {
        super(client);
        this.client = client;
        this.config = config;
        this.manager = manager;
        this.dataSources = config.getDataSources();
        this.tableLatestSuccess = new HashMap<>();
    }

    @Override
    public void deleteHistory(Model model, String timeBucketColumnName, int ttl) throws IOException {
        if (!model.isRecord()) {
            if (!DownSampling.Minute.equals(model.getDownsampling())) {
                return;
            }
        }

        long deadline = Long.parseLong(DateTime.now().plusDays(-ttl).toString("yyyyMMdd"));
        //If it's a sharding table drop expired tables
        if (model.getSqlDBModelExtension().isShardingTable()) {
            boolean isRuleExecuted = false;
            Long latestSuccessDeadline = this.tableLatestSuccess.get(model.getName());
            if (latestSuccessDeadline != null && deadline <= latestSuccessDeadline) {
                if (log.isDebugEnabled()) {
                    log.debug("Table = {} already deleted, skip, deadline = {}, ttl = {}", model.getName(), deadline, ttl);
                }
                return;
            }
            try {
                //refresh sharding rules
                isRuleExecuted = ShardingRulesOperator.INSTANCE.createOrUpdateShardingRule(client, model, this.dataSources, ttl);
                if (isRuleExecuted) {
                    MySQLShardingTableInstaller installer = new MySQLShardingTableInstaller(client, manager, config);
                    installer.createTable(model);
                }
            } catch (StorageException e) {
                throw new IOException(e.getMessage(), e);
            }
            List<String> realTables = new ArrayList<>();
            try (Connection connection = client.getConnection()) {
                ResultSet resultSet = connection.getMetaData()
                                                .getTables(connection.getCatalog(), null, model.getName() + "_20%", null);
                while (resultSet.next()) {
                    realTables.add(resultSet.getString("TABLE_NAME"));
                }

                //delete additional tables
                for (String additionalTable : model.getSqlDBModelExtension().getAdditionalTables().keySet()) {
                    ResultSet additionalTableRS = connection.getMetaData()
                                                            .getTables(connection.getCatalog(), null,
                                                                       additionalTable + "_20%", null);
                    while (additionalTableRS.next()) {
                        realTables.add(additionalTableRS.getString("TABLE_NAME"));
                    }
                }
            } catch (JDBCClientException | SQLException e) {
                throw new IOException(e.getMessage(), e);
            }

            List<String> prepareDeleteTables = new ArrayList<>();
            for (String table : realTables) {
                long timeSeries = isolateTimeFromTableName(table);
                if (deadline >= timeSeries) {
                    prepareDeleteTables.add(table);
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("Tables to be dropped: {}", prepareDeleteTables);
            }

            try (Connection connection = client.getConnection()) {
                Set<String> dsList = config.getDataSources();
                for (String prepareDeleteTable : prepareDeleteTables) {
                    for (String ds : dsList) {
                        client.execute(connection, getDropSQL(ds, prepareDeleteTable));
                    }
                }
            } catch (JDBCClientException | SQLException e) {
                throw new IOException(e.getMessage(), e);
            }
            this.tableLatestSuccess.put(model.getName(), deadline);
        } else {
            //Delete rows as previous
            super.deleteHistory(model, timeBucketColumnName, ttl);
        }
    }

    private long isolateTimeFromTableName(String tableName) {
        return Long.parseLong(tableName.substring(tableName.lastIndexOf("_") + 1));
    }

    private String getDropSQL(String dataSource, String table) {
        String dropSQL = "/* ShardingSphere hint: dataSourceName=" + dataSource + "*/\n" +
            "drop table if exists " + table;

        return dropSQL;
    }
}
