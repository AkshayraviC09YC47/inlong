/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.standalone.source.sortsdk;

import com.google.common.base.Preconditions;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang.ClassUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.flume.Context;
import org.apache.flume.EventDrivenSource;
import org.apache.flume.conf.Configurable;
import org.apache.flume.source.AbstractSource;
import org.apache.inlong.common.pojo.sortstandalone.SortTaskConfig;
import org.apache.inlong.sdk.sort.api.QueryConsumeConfig;
import org.apache.inlong.sdk.sort.api.SortClient;
import org.apache.inlong.sdk.sort.api.SortClientConfig;
import org.apache.inlong.sdk.sort.api.SortClientFactory;
import org.apache.inlong.sdk.sort.impl.ManagerReportHandlerImpl;
import org.apache.inlong.sdk.sort.impl.MetricReporterImpl;
import org.apache.inlong.sort.standalone.config.holder.CommonPropertiesHolder;
import org.apache.inlong.sort.standalone.config.holder.ManagerUrlHandler;
import org.apache.inlong.sort.standalone.config.holder.SortClusterConfigHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default Source implementation of InLong.
 *
 * <p> SortSdkSource acquired msg from different upstream data store by register {@link SortClient} for each
 * sort task. The only things SortSdkSource should do is to get one client by the sort task id, or remove one client
 * when the task is finished or schedule to other source instance. </p>
 *
 * <p> The Default Manager of InLong will schedule the partition and topic automatically. </p>
 *
 * <p> Because all sources should implement {@link Configurable}, the SortSdkSource should have
 * default constructor <b>WITHOUT</b> any arguments, and parameters will be configured by
 * {@link Configurable#configure(Context)}. </p>
 */
public final class SortSdkSource extends AbstractSource implements Configurable, Runnable, EventDrivenSource {

    // Log of {@link SortSdkSource}.
    private static final Logger LOG = LoggerFactory.getLogger(SortSdkSource.class);

    // KEY of QueryConsumeConfig Type
    private static final String KEY_QUERY_CONSUME_CONFIG_TYPE =
            "sortSourceConfig.QueryConsumeConfigType";

    // Default pool of {@link ScheduledExecutorService}.
    private static final int CORE_POOL_SIZE = 1;

    // Default consume strategy of {@link SortClient}.
    private static final SortClientConfig.ConsumeStrategy defaultStrategy = SortClientConfig.ConsumeStrategy.lastest;

    // Map of {@link SortClient}.
    private Map<String, SortClient> clients;

    // The cluster name of sort.
    private String sortClusterName;

    // Reload config interval.
    private long reloadInterval;

    // Context of SortSdkSource.
    private SortSdkSourceContext context;

    // Executor for config reloading.
    private ScheduledExecutorService pool;

    /**
     * Start SortSdkSource.
     */
    @Override
    public synchronized void start() {
        this.reloadAll();
    }

    /**
     * Stop {@link #pool} and close all {@link SortClient}.
     */
    @Override
    public void stop() {
        pool.shutdownNow();
        clients.forEach((sortId, client) -> client.close());
    }

    /**
     * Entrance of {@link #pool} to reload clients with fix rate {@link #reloadInterval}.
     */
    @Override
    public void run() {
        this.reloadAll();
    }

    /**
     * Configure parameters.
     *
     * @param context Context of source.
     */
    @Override
    public void configure(Context context) {
        this.clients = new ConcurrentHashMap<>();
        this.sortClusterName = SortClusterConfigHolder.getClusterConfig().getClusterName();
        Preconditions.checkState(context != null, "No context, configure failed");
        this.context = new SortSdkSourceContext(getName(), context);
        this.reloadInterval = this.context.getReloadInterval();
        this.initReloadExecutor();

    }

    /**
     * Init ScheduledExecutorService with fix reload rate {@link #reloadInterval}.
     */
    private void initReloadExecutor() {
        this.pool = Executors.newScheduledThreadPool(CORE_POOL_SIZE);
        pool.scheduleAtFixedRate(this, reloadInterval, reloadInterval, TimeUnit.SECONDS);
    }

    /**
     * Reload clients by current {@link SortTaskConfig}.
     *
     * <p> Create new clients with new sort task id, and remove the finished or scheduled ones. </p>
     *
     * <p> Current version of SortSdk <b>DO NOT</b> support to get the corresponding sort id of {@link SortClient}.
     * Hence, the maintenance of mapping of {@literal sortId, SortClient} should be done by Source itself.
     */
    private void reloadAll() {

        final List<SortTaskConfig> configs = SortClusterConfigHolder.getClusterConfig().getSortTasks();
        LOG.info("start to reload SortSdkSource");
        this.startNewClients(configs);
        this.stopExpiryClients(configs);
        this.updateAllClientConfig();
    }

    /**
     * Start a new client from SortTaskConfig.
     * <p>
     *     If the sortId is in configs, but not in active clients, start it.
     * </p>
     *
     * @param configs Updated SortTaskConfig
     */
    private void startNewClients(final List<SortTaskConfig> configs) {
        configs.stream()
                .map(SortTaskConfig::getName)
                .filter(sortId -> !clients.containsKey(sortId))
                .forEach(sortId -> {
                    final SortClient client = this.newClient(sortId);
                    Optional.ofNullable(client)
                            .ifPresent(c -> clients.put(sortId, c));
                });
    }

    /**
     * Stop an expiry client from SortTaskConfig.
     * <p>
     *     If the sortId is in active clients, but not in configs, stop it.
     * </p>
     *
     * @param configs Updated SortTaskConfig
     */
    private void stopExpiryClients(final List<SortTaskConfig> configs) {
        Set<String> updatedSortIds = configs.stream()
                .map(SortTaskConfig::getName)
                .collect(Collectors.toSet());

        clients.keySet().stream()
                .filter(sortId -> !updatedSortIds.contains(sortId))
                .forEach(sortId -> {
                    final SortClient client = clients.get(sortId);
                    LOG.info("Close sort client {}.", sortId);
                    try {
                        client.close();
                    } catch (Throwable th) {
                        LOG.error("Got a throwable when close client {}, {}", sortId, th.getMessage());
                    }
                    clients.remove(sortId);
                });
    }

    /**
     * Update all client config.
     */
    private void updateAllClientConfig() {
        clients.values().stream()
                .map(SortClient::getConfig)
                .forEach(this::updateClientConfig);
    }

    /**
     * Update one client config.
     *
     * @param config The config to be updated.
     */
    private void updateClientConfig(SortClientConfig config) {
        config.setManagerApiUrl(ManagerUrlHandler.getSortSourceConfigUrl());
    }

    /**
     * Create one {@link SortClient} with specific sort id.
     *
     * <p> In current version, the {@link FetchCallback} will hold the client to ACK.
     * For more details see {@link FetchCallback#onFinished}</p>
     *
     * @param sortId Sort in of new client.
     * @return New sort client.
     */
    private SortClient newClient(final String sortId) {
        LOG.info("Start to new sort client for id: {}", sortId);
        try {
            final SortClientConfig clientConfig =
                    new SortClientConfig(sortId, this.sortClusterName, new DefaultTopicChangeListener(),
                            SortSdkSource.defaultStrategy, InetAddress.getLocalHost().getHostAddress());
            final FetchCallback callback = FetchCallback.Factory.create(sortId, getChannelProcessor(), context);
            clientConfig.setCallback(callback);
            this.updateClientConfig(clientConfig);
            SortClient client;
            QueryConsumeConfig queryConsumeConfigImpl = this.getQueryConfigImpl();
            if (queryConsumeConfigImpl != null) {
                // if it specifies the type of QueryConsumeConfig.
                LOG.info("Create sort sdk client in custom way.");
                client = SortClientFactory.createSortClient(clientConfig,
                                queryConsumeConfigImpl,
                                new MetricReporterImpl(clientConfig),
                                new ManagerReportHandlerImpl());
            } else {
                LOG.info("Create sort sdk client in default way.");
                client = SortClientFactory.createSortClient(clientConfig);
            }
            client.init();
            // temporary use to ACK fetched msg.
            callback.setClient(client);
            return client;
        } catch (UnknownHostException ex) {
            LOG.error("Got one UnknownHostException when init client of id: " + sortId, ex);
        } catch (Throwable th) {
            LOG.error("Got one throwable when init client of id: " + sortId, th);
        }
        return null;
    }

    private QueryConsumeConfig getQueryConfigImpl() {
        String className = CommonPropertiesHolder.getString(KEY_QUERY_CONSUME_CONFIG_TYPE);
        if (StringUtils.isBlank(className)) {
            LOG.info("There is no property of {}, use default implementation.", KEY_QUERY_CONSUME_CONFIG_TYPE);
            return null;
        }
        LOG.info("Start to load QueryConfig class {}.", className);
        try {
            Class<?> queryConfigType = ClassUtils.getClass(className);
            Object obj = queryConfigType.getDeclaredConstructor().newInstance();
            if (obj instanceof QueryConsumeConfig) {
                LOG.info("Load {} successfully.", className);
                return (QueryConsumeConfig) obj;
            }
        } catch (Throwable t) {
            LOG.error("Got exception when load QueryConfigImpl, class name is " + className + ". Exception is "
                    + t.getMessage(), t);
        }
        return null;
    }

}
