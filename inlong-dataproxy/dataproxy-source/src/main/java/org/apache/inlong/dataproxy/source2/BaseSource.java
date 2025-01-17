/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.dataproxy.source2;

import com.google.common.base.Preconditions;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.apache.commons.lang3.StringUtils;
import org.apache.flume.ChannelSelector;
import org.apache.flume.Context;
import org.apache.flume.EventDrivenSource;
import org.apache.flume.FlumeException;
import org.apache.flume.conf.Configurable;
import org.apache.flume.source.AbstractSource;
import org.apache.inlong.common.metric.MetricRegister;
import org.apache.inlong.common.monitor.MonitorIndex;
import org.apache.inlong.common.monitor.MonitorIndexExt;
import org.apache.inlong.dataproxy.admin.ProxyServiceMBean;
import org.apache.inlong.dataproxy.channel.FailoverChannelProcessor;
import org.apache.inlong.dataproxy.config.CommonConfigHolder;
import org.apache.inlong.dataproxy.metrics.DataProxyMetricItemSet;
import org.apache.inlong.dataproxy.utils.ConfStringUtils;
import org.apache.inlong.dataproxy.utils.FailoverChannelProcessorHolder;
import org.apache.inlong.sdk.commons.admin.AdminServiceRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.Map;

/**
 * source base class
 *
 */
public abstract class BaseSource
        extends
            AbstractSource
        implements
            ProxyServiceMBean,
            EventDrivenSource,
            Configurable {

    private static final Logger logger = LoggerFactory.getLogger(BaseSource.class);

    protected Context context;
    // whether source reject service
    protected volatile boolean isRejectService = false;
    // source service host
    protected String srcHost;
    // source serviced port
    protected int srcPort;
    protected String strPort;
    // message factory name
    protected String msgFactoryName;
    // message handler name
    protected String messageHandlerName;
    // source default topic
    protected String defTopic = "";
    // source default append attribute
    protected String defAttr = "";
    // allowed max message length
    protected int maxMsgLength;
    // whether compress message
    protected boolean isCompressed;
    // whether filter empty message
    protected boolean filterEmptyMsg;
    // whether custom channel processor
    protected boolean customProcessor;
    // max netty worker threads
    protected int maxWorkerThreads;
    // max netty accept threads
    protected int maxAcceptThreads;
    // max read idle time
    protected long maxReadIdleTimeMs;
    // max connection count
    protected int maxConnections;
    // netty parameters
    protected EventLoopGroup acceptorGroup;
    protected EventLoopGroup workerGroup;
    protected ChannelGroup allChannels;
    protected ChannelFuture channelFuture;
    // receive buffer size
    protected int maxRcvBufferSize;
    // send buffer size
    protected int maxSendBufferSize;
    // file metric statistic
    protected boolean fileMetricOn;
    protected int monitorStatInvlSec;
    protected int maxMonitorStatCnt;
    protected MonitorIndex monitorIndex = null;
    private MonitorIndexExt monitorIndexExt = null;
    // metric set
    protected DataProxyMetricItemSet metricItemSet;

    public BaseSource() {
        super();
        allChannels = new DefaultChannelGroup("DefaultChannelGroup", GlobalEventExecutor.INSTANCE);
    }

    @Override
    public void configure(Context context) {
        this.context = context;
        this.srcHost = getHostIp(context);
        this.srcPort = getHostPort(context);
        this.strPort = String.valueOf(this.srcPort);
        // get message factory
        String tmpVal = context.getString(SourceConstants.SRCCXT_MSG_FACTORY_NAME,
                InLongMessageFactory.class.getName()).trim();
        Preconditions.checkArgument(StringUtils.isNotBlank(tmpVal),
                SourceConstants.SRCCXT_MSG_FACTORY_NAME + " config is blank");
        this.msgFactoryName = tmpVal.trim();
        // get message handler
        tmpVal = context.getString(SourceConstants.SRCCXT_MESSAGE_HANDLER_NAME,
                InLongMessageHandler.class.getName().trim());
        Preconditions.checkArgument(StringUtils.isNotBlank(tmpVal),
                SourceConstants.SRCCXT_MESSAGE_HANDLER_NAME + " config is blank");
        this.messageHandlerName = tmpVal;
        // get default topic
        tmpVal = context.getString(SourceConstants.SRCCXT_DEF_TOPIC);
        if (StringUtils.isNotBlank(tmpVal)) {
            this.defTopic = tmpVal.trim();
        }
        // get default attributes
        tmpVal = context.getString(SourceConstants.SRCCXT_DEF_ATTR);
        if (StringUtils.isNotBlank(tmpVal)) {
            this.defAttr = tmpVal.trim();
        }
        // get allowed max message length
        this.maxMsgLength = getIntValue(context, SourceConstants.SRCCXT_MAX_MSG_LENGTH,
                SourceConstants.VAL_DEF_MAX_MSG_LENGTH);
        Preconditions.checkArgument((this.maxMsgLength >= SourceConstants.VAL_MIN_MAX_MSG_LENGTH
                && this.maxMsgLength <= SourceConstants.VAL_MAX_MAX_MSG_LENGTH),
                SourceConstants.SRCCXT_MAX_MSG_LENGTH + " must be in ["
                        + SourceConstants.VAL_MIN_MAX_MSG_LENGTH + ", "
                        + SourceConstants.VAL_MAX_MAX_MSG_LENGTH + "]");
        // get whether compress message
        this.isCompressed = context.getBoolean(SourceConstants.SRCCXT_MSG_COMPRESSED,
                SourceConstants.VAL_DEF_MSG_COMPRESSED);
        // get whether filter empty message
        this.filterEmptyMsg = context.getBoolean(SourceConstants.SRCCXT_FILTER_EMPTY_MSG,
                SourceConstants.VAL_DEF_FILTER_EMPTY_MSG);
        // get whether custom channel processor
        this.customProcessor = context.getBoolean(SourceConstants.SRCCXT_CUSTOM_CHANNEL_PROCESSOR,
                SourceConstants.VAL_DEF_CUSTOM_CH_PROCESSOR);
        // get max accept threads
        this.maxAcceptThreads = getIntValue(context, SourceConstants.SRCCXT_MAX_ACCEPT_THREADS,
                SourceConstants.VAL_DEF_NET_ACCEPT_THREADS);
        Preconditions.checkArgument((this.maxAcceptThreads >= SourceConstants.VAL_MIN_ACCEPT_THREADS
                && this.maxAcceptThreads <= SourceConstants.VAL_MAX_ACCEPT_THREADS),
                SourceConstants.SRCCXT_MAX_ACCEPT_THREADS + " must be in ["
                        + SourceConstants.VAL_MIN_ACCEPT_THREADS + ", "
                        + SourceConstants.VAL_MAX_ACCEPT_THREADS + "]");
        // get max worker threads
        this.maxWorkerThreads = getIntValue(context, SourceConstants.SRCCXT_MAX_WORKER_THREADS,
                SourceConstants.VAL_DEF_WORKER_THREADS);
        Preconditions.checkArgument((this.maxWorkerThreads >= SourceConstants.VAL_MIN_WORKER_THREADS
                && this.maxWorkerThreads <= SourceConstants.VAL_MAX_WORKER_THREADS),
                SourceConstants.SRCCXT_MAX_WORKER_THREADS + " must be in ["
                        + SourceConstants.VAL_MIN_WORKER_THREADS + ", "
                        + SourceConstants.VAL_MAX_WORKER_THREADS + "]");
        // get max read idle time
        this.maxReadIdleTimeMs = getLongValue(context, SourceConstants.SRCCXT_MAX_READ_IDLE_TIME_MS,
                SourceConstants.VAL_DEF_READ_IDLE_TIME_MS);
        Preconditions.checkArgument((this.maxReadIdleTimeMs >= SourceConstants.VAL_MIN_READ_IDLE_TIME_MS),
                SourceConstants.SRCCXT_MAX_READ_IDLE_TIME_MS + " must be >= "
                        + SourceConstants.VAL_MIN_READ_IDLE_TIME_MS);
        // get file metric statistic
        this.monitorStatInvlSec = getIntValue(context, SourceConstants.SRCCXT_STAT_INTERVAL_SEC,
                SourceConstants.VAL_DEF_STAT_INVL_SEC);
        Preconditions.checkArgument((this.monitorStatInvlSec >= SourceConstants.VAL_MIN_STAT_INVL_SEC),
                SourceConstants.SRCCXT_STAT_INTERVAL_SEC + " must be >= "
                        + SourceConstants.VAL_MIN_STAT_INVL_SEC);
        // get max monitor key count
        this.maxMonitorStatCnt = getIntValue(context, SourceConstants.SRCCXT_MAX_MONITOR_STAT_CNT,
                SourceConstants.VAL_DEF_MON_STAT_CNT);
        Preconditions.checkArgument(this.maxMonitorStatCnt >= SourceConstants.VAL_MIN_MON_STAT_CNT,
                SourceConstants.SRCCXT_MAX_MONITOR_STAT_CNT + " must be >= "
                        + SourceConstants.VAL_MIN_MON_STAT_CNT);
        // get max connect count
        this.maxConnections = getIntValue(context, SourceConstants.SRCCXT_MAX_CONNECTION_CNT,
                SourceConstants.VAL_DEF_MAX_CONNECTION_CNT);
        Preconditions.checkArgument(this.maxConnections >= SourceConstants.VAL_MIN_CONNECTION_CNT,
                SourceConstants.SRCCXT_MAX_CONNECTION_CNT + " must be >= "
                        + SourceConstants.VAL_MIN_CONNECTION_CNT);
        // get whether enable file metric
        this.fileMetricOn = context.getBoolean(SourceConstants.SRCCXT_FILE_METRIC_ON,
                SourceConstants.VAL_DEF_FILE_METRIC_ON);
        // get max receive buffer size
        this.maxRcvBufferSize = getIntValue(context, SourceConstants.SRCCXT_RECEIVE_BUFFER_SIZE,
                SourceConstants.VAL_DEF_RECEIVE_BUFFER_SIZE);
        Preconditions.checkArgument(this.maxRcvBufferSize >= SourceConstants.VAL_MIN_RECEIVE_BUFFER_SIZE,
                SourceConstants.SRCCXT_RECEIVE_BUFFER_SIZE + " must be >= "
                        + SourceConstants.VAL_MIN_RECEIVE_BUFFER_SIZE);
        if (this.maxRcvBufferSize > SourceConstants.VAL_MAX_RECEIVE_BUFFER_SIZE) {
            this.maxRcvBufferSize = SourceConstants.VAL_MAX_RECEIVE_BUFFER_SIZE;
        }
        // get max send buffer size
        this.maxSendBufferSize = getIntValue(context, SourceConstants.SRCCXT_SEND_BUFFER_SIZE,
                SourceConstants.VAL_DEF_SEND_BUFFER_SIZE);
        Preconditions.checkArgument(this.maxSendBufferSize >= SourceConstants.VAL_MIN_SEND_BUFFER_SIZE,
                SourceConstants.SRCCXT_SEND_BUFFER_SIZE + " must be >= "
                        + SourceConstants.VAL_MIN_SEND_BUFFER_SIZE);
        if (this.maxSendBufferSize > SourceConstants.VAL_MAX_SEND_BUFFER_SIZE) {
            this.maxSendBufferSize = SourceConstants.VAL_MAX_SEND_BUFFER_SIZE;
        }
    }

    @Override
    public synchronized void start() {
        if (customProcessor) {
            ChannelSelector selector = getChannelProcessor().getSelector();
            FailoverChannelProcessor newProcessor = new FailoverChannelProcessor(selector);
            newProcessor.configure(this.context);
            setChannelProcessor(newProcessor);
            FailoverChannelProcessorHolder.setChannelProcessor(newProcessor);
        }
        super.start();
        // initial metric item set
        this.metricItemSet = new DataProxyMetricItemSet(
                CommonConfigHolder.getInstance().getClusterName(), getName(), String.valueOf(srcPort));
        MetricRegister.register(metricItemSet);
        // init monitor logic
        if (fileMetricOn) {
            this.monitorIndex = new MonitorIndex("Source", monitorStatInvlSec, maxMonitorStatCnt);
            this.monitorIndexExt = new MonitorIndexExt(
                    "DataProxy_monitors#" + this.getProtocolName(), monitorStatInvlSec, maxMonitorStatCnt);
        }
        startSource();
        // register
        AdminServiceRegister.register(ProxyServiceMBean.MBEAN_TYPE, this.getName(), this);
    }

    @Override
    public synchronized void stop() {
        logger.info("[STOP {} SOURCE]{} stopping...", this.getProtocolName(), this.getName());
        // close channels
        if (!allChannels.isEmpty()) {
            try {
                allChannels.close().awaitUninterruptibly();
            } catch (Exception e) {
                logger.warn("Close {} netty channels throw exception", this.getName(), e);
            } finally {
                allChannels.clear();
            }
        }
        // close channel future
        if (channelFuture != null) {
            try {
                channelFuture.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                logger.warn("Close {} channel future throw exception", this.getName(), e);
            }
        }
        // stop super class
        super.stop();
        // stop file statistic index
        if (fileMetricOn) {
            if (monitorIndex != null) {
                monitorIndex.shutDown();
            }
            if (monitorIndexExt != null) {
                monitorIndexExt.shutDown();
            }
        }
        logger.info("[STOP {} SOURCE]{} stopped", this.getProtocolName(), this.getName());
    }

    /**
     * get metricItemSet
     * @return the metricItemSet
     */
    public DataProxyMetricItemSet getMetricItemSet() {
        return metricItemSet;
    }

    public Context getContext() {
        return context;
    }

    public String getSrcHost() {
        return srcHost;
    }

    public int getSrcPort() {
        return srcPort;
    }

    public String getStrPort() {
        return strPort;
    }

    public String getDefTopic() {
        return defTopic;
    }

    public String getDefAttr() {
        return defAttr;
    }

    public int getMaxMsgLength() {
        return maxMsgLength;
    }

    public boolean isCompressed() {
        return isCompressed;
    }

    public boolean isFilterEmptyMsg() {
        return filterEmptyMsg;
    }

    public boolean isCustomProcessor() {
        return customProcessor;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public ChannelGroup getAllChannels() {
        return allChannels;
    }

    public long getMaxReadIdleTimeMs() {
        return maxReadIdleTimeMs;
    }

    public String getMessageHandlerName() {
        return messageHandlerName;
    }

    public int getMaxWorkerThreads() {
        return maxWorkerThreads;
    }

    public void fileMetricEventInc(String eventKey) {
        if (fileMetricOn) {
            monitorIndexExt.incrementAndGet(eventKey);
        }
    }

    public void fileMetricRecordAdd(String key, int cnt, int packCnt, long packSize, int failCnt) {
        if (fileMetricOn) {
            monitorIndex.addAndGet(key, cnt, packCnt, packSize, failCnt);
        }
    }

    /**
     * channel factory
     * @return
     */
    public ChannelInitializer getChannelInitializerFactory() {
        ChannelInitializer fac = null;
        logger.info(this.getName() + " load msgFactory=" + msgFactoryName);
        try {
            Class<? extends ChannelInitializer> clazz =
                    (Class<? extends ChannelInitializer>) Class.forName(msgFactoryName);
            Constructor ctor = clazz.getConstructor(BaseSource.class);
            logger.info("Using channel processor:{}", getChannelProcessor().getClass().getName());
            fac = (ChannelInitializer) ctor.newInstance(this);
        } catch (Exception e) {
            logger.error("{} start error, fail to construct ChannelPipelineFactory with name {}",
                    this.getName(), msgFactoryName, e);
            stop();
            throw new FlumeException(e.getMessage());
        }
        return fac;
    }

    public abstract String getProtocolName();

    public abstract void startSource();

    /**
     * stopService
     */
    @Override
    public void stopService() {
        this.isRejectService = true;
    }

    /**
     * recoverService
     */
    @Override
    public void recoverService() {
        this.isRejectService = false;
    }

    /**
     * isRejectService
     *
     * @return
     */
    public boolean isRejectService() {
        return isRejectService;
    }

    /**
     * Get the configuration value of integer type from the context
     *
     * @param context  the context
     * @param fieldKey the configure key
     * @param defVal   the default value
     *
     * @return the configuration value
     */
    public int getIntValue(Context context, String fieldKey, int defVal) {
        String tmpVal = context.getString(fieldKey);
        if (StringUtils.isNotBlank(tmpVal)) {
            int result;
            tmpVal = tmpVal.trim();
            try {
                result = Integer.parseInt(tmpVal);
            } catch (Throwable e) {
                throw new IllegalArgumentException(
                        fieldKey + "(" + tmpVal + ") must specify an integer value!");
            }
            return result;
        }
        return defVal;
    }

    /**
     * Get the configuration value of long type from the context
     *
     * @param context  the context
     * @param fieldKey the configure key
     * @param defVal   the default value
     *
     * @return the configuration value
     */
    public long getLongValue(Context context, String fieldKey, long defVal) {
        String tmpVal = context.getString(fieldKey);
        if (StringUtils.isNotBlank(tmpVal)) {
            long result;
            tmpVal = tmpVal.trim();
            try {
                result = Long.parseLong(tmpVal);
            } catch (Throwable e) {
                throw new IllegalArgumentException(
                        fieldKey + "(" + tmpVal + ") must specify an long value!");
            }
            return result;
        }
        return defVal;
    }

    /**
     * getHostIp
     *
     * @param  context
     * @return
     */
    private String getHostIp(Context context) {
        String result = null;
        // first get host ip from dataProxy.conf
        String tmpVal = context.getString(SourceConstants.SRCCXT_CONFIG_HOST);
        if (StringUtils.isNotBlank(tmpVal)) {
            tmpVal = tmpVal.trim();
            Preconditions.checkArgument(ConfStringUtils.isValidIp(tmpVal),
                    SourceConstants.SRCCXT_CONFIG_HOST + "(" + tmpVal + ") config in conf not valid");
            result = tmpVal;
        }
        // second get host ip from system env
        Map<String, String> envMap = System.getenv();
        if (envMap.containsKey(SourceConstants.SYSENV_HOST_IP)) {
            tmpVal = envMap.get(SourceConstants.SYSENV_HOST_IP);
            Preconditions.checkArgument(ConfStringUtils.isValidIp(tmpVal),
                    SourceConstants.SYSENV_HOST_IP + "(" + tmpVal + ") config in system env not valid");
            result = tmpVal.trim();
        }
        if (StringUtils.isBlank(result)) {
            result = SourceConstants.VAL_DEF_HOST_VALUE;
        }
        return result;
    }

    /**
     * getHostPort
     *
     * @param  context
     * @return
     */
    private int getHostPort(Context context) {
        Integer result = null;
        // first get host port from dataProxy.conf
        String tmpVal = context.getString(SourceConstants.SRCCXT_CONFIG_PORT);
        if (StringUtils.isNotBlank(tmpVal)) {
            tmpVal = tmpVal.trim();
            try {
                result = Integer.parseInt(tmpVal);
            } catch (Throwable e) {
                throw new IllegalArgumentException(
                        SourceConstants.SYSENV_HOST_PORT + "(" + tmpVal + ") config in conf not integer");
            }
        }
        if (result != null) {
            Preconditions.checkArgument(ConfStringUtils.isValidPort(result),
                    SourceConstants.SRCCXT_CONFIG_PORT + "(" + result + ") config in conf not valid");
        }
        // second get host port from system env
        Map<String, String> envMap = System.getenv();
        if (envMap.containsKey(SourceConstants.SYSENV_HOST_PORT)) {
            tmpVal = envMap.get(SourceConstants.SYSENV_HOST_PORT);
            if (StringUtils.isNotBlank(tmpVal)) {
                tmpVal = tmpVal.trim();
                try {
                    result = Integer.parseInt(tmpVal);
                } catch (Throwable e) {
                    throw new IllegalArgumentException(
                            SourceConstants.SYSENV_HOST_PORT + "(" + tmpVal + ") config in system env not integer");
                }
                Preconditions.checkArgument(ConfStringUtils.isValidPort(result),
                        SourceConstants.SYSENV_HOST_PORT + "(" + tmpVal + ") config in system env not valid");
            }
        }
        if (result == null) {
            throw new IllegalArgumentException("Required parameter " +
                    SourceConstants.SRCCXT_CONFIG_PORT + " must exist and may not be null");
        }
        return result;
    }

}
