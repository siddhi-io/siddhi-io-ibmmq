/*
 *  Copyright (c) 2018 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package io.siddhi.extension.io.ibmmq.source;


import com.ibm.mq.jms.MQQueueConnectionFactory;
import com.ibm.msg.client.wmq.WMQConstants;

import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.util.DataType;
import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.exception.ConnectionUnavailableException;
import io.siddhi.core.stream.ServiceDeploymentInfo;
import io.siddhi.core.stream.input.source.Source;
import io.siddhi.core.stream.input.source.SourceEventListener;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.snapshot.state.State;
import io.siddhi.core.util.snapshot.state.StateFactory;
import io.siddhi.core.util.transport.OptionHolder;

import io.siddhi.extension.io.ibmmq.sink.exception.IBMMQSinkAdaptorRuntimeException;
import io.siddhi.extension.io.ibmmq.source.exception.IBMMQSourceAdaptorRuntimeException;
import io.siddhi.extension.io.ibmmq.util.IBMMQConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

import javax.jms.JMSException;

/**
 * IBM MQ Source implementation.
 */
@Extension(
        name = "ibmmq",
        namespace = "source",
        description = "IBM MQ source allows you to subscribe to an IBM message queue and receive messages. It has the "
                + "ability to receive messages of the 'map' and 'text' message formats.",
        parameters = {
                @Parameter(name = IBMMQConstants.DESTINATION_NAME,
                        description = "The name of the queue name to which the IBM MQ source should subscribe.",
                        type = DataType.STRING),
                @Parameter(name = IBMMQConstants.HOST,
                        description = "The host address of the IBM MQ server.",
                        type = DataType.STRING),
                @Parameter(name = IBMMQConstants.PORT,
                        description = "The port of the IBM MQ server.",
                        type = DataType.STRING),
                @Parameter(name = IBMMQConstants.CHANNEL,
                        description = "The channel used to connect to the MQ server.",
                        type = DataType.STRING),
                @Parameter(name = IBMMQConstants.QUEUE_MANAGER_NAME,
                        description = "The name of the queue manager.",
                        type = DataType.STRING),
                @Parameter(name = IBMMQConstants.USER_NAME,
                        description = "The username to connect to the server. If this is not provided, the " +
                                "connection is attempted without both the username and the password.",
                        type = DataType.STRING,
                        optional = true,
                        defaultValue = "null"),
                @Parameter(name = IBMMQConstants.PASSWORD,
                        description = "The password to connect to the server. If this is not provided, the " +
                                "connection is attempted without both the username and the password.",
                        type = DataType.STRING,
                        optional = true,
                        defaultValue = "null"),
                @Parameter(name = IBMMQConstants.WORKER_COUNT,
                        description = "Number of worker threads listening on the given queue. When the multiple " +
                                "workers are enabled event ordering is not preserved.",
                        type = DataType.INT,
                        optional = true,
                        defaultValue = "1"),
                @Parameter(name = IBMMQConstants.PROPERTIES,
                        description = "IBM MQ properties which are supported by the client can be provided as " +
                                "key value pairs which is separated by \",\". as an example " +
                                "batch.properties = 'XMSC_WMQ_CLIENT_RECONNECT_OPTIONS:1600," +
                                "WMQ_CLIENT_RECONNECT:5005' ",
                        type = DataType.STRING,
                        optional = true,
                        defaultValue = "null"),
                @Parameter(name = IBMMQConstants.CLIENT_RECONNECT_TIMEOUT,
                        description = "The time a client waits for reconnection. The value should be provided in " +
                                "seconds.",
                        type = DataType.INT,
                        optional = true,
                        defaultValue = "30")
        },
        examples = {
                @Example(syntax = "@source(type='ibmmq',"
                        + "destination.name='Queue1',"
                        + "host='192.168.56.3',"
                        + "port='1414',"
                        + "channel='Channel1',"
                        + "queue.manager = 'ESBQManager',"
                        + "password='1920',"
                        + "username='mqm',"
                        + "batch.properties = 'XMSC_WMQ_CLIENT_RECONNECT_OPTIONS:1600,WMQ_CLIENT_RECONNECT:5005',"
                        + "@map(type='text'))"
                        + "define stream SweetProductionStream(name string, amount double);",
                        description = "This exampe shows how to connect to an IBM message queue and receive messages."),
        }
)

public class IBMMQSource extends Source {
    private static final Logger logger = LoggerFactory.getLogger(IBMMQSource.class);
    private SourceEventListener sourceEventListener;
    private MQQueueConnectionFactory connectionFactory;
    private IBMMessageConsumerGroup ibmMessageConsumerGroup;
    private ScheduledExecutorService scheduledExecutorService;
    private IBMMessageConsumerBean ibmMessageConsumerBean = new IBMMessageConsumerBean();
    private SiddhiAppContext siddhiAppContext;
    private String properties;
    private ServiceDeploymentInfo serviceDeploymentInfo;

    @Override
    public StateFactory init(SourceEventListener sourceEventListener, OptionHolder optionHolder,
                             String[] requestedTransportPropertyNames, ConfigReader configReader,
                             SiddhiAppContext siddhiAppContext) {
        this.sourceEventListener = sourceEventListener;
        this.siddhiAppContext = siddhiAppContext;
        this.properties = optionHolder.validateAndGetStaticValue(IBMMQConstants.PROPERTIES, configReader.readConfig
                (IBMMQConstants.PROPERTIES, null));
        if (properties != null) {
            ibmMessageConsumerBean.setPropertyMap(generatePropertyMap(properties));
        }
        this.connectionFactory = new MQQueueConnectionFactory();
        ibmMessageConsumerBean.setQueueName(optionHolder.validateAndGetStaticValue(IBMMQConstants.DESTINATION_NAME,
                configReader.readConfig(IBMMQConstants.DESTINATION_NAME, null)));
        ibmMessageConsumerBean.setUserName(optionHolder.validateAndGetStaticValue(IBMMQConstants.USER_NAME,
                configReader.readConfig(IBMMQConstants.USER_NAME, null)));
        ibmMessageConsumerBean.setPassword(optionHolder.validateAndGetStaticValue(IBMMQConstants.PASSWORD,
                configReader.readConfig(IBMMQConstants.PASSWORD, null)));
        ibmMessageConsumerBean.setWorkerCount(Integer.parseInt(optionHolder.validateAndGetStaticValue(
                IBMMQConstants.WORKER_COUNT, "1")));
        ibmMessageConsumerBean.setDestinationName(optionHolder.validateAndGetOption(IBMMQConstants.DESTINATION_NAME)
                .getValue());
        if (Objects.nonNull(ibmMessageConsumerBean.getPassword()) &&
                Objects.nonNull(ibmMessageConsumerBean.getUserName())) {
            ibmMessageConsumerBean.setSecured(true);
        }
        try {
            connectionFactory.setChannel(optionHolder.validateAndGetOption(IBMMQConstants.CHANNEL).getValue());
            connectionFactory.setHostName(optionHolder.validateAndGetOption(IBMMQConstants.HOST).getValue());
            connectionFactory.setPort(Integer.parseInt(optionHolder.
                    validateAndGetOption(IBMMQConstants.PORT).getValue()));
            connectionFactory.setQueueManager(optionHolder.validateAndGetOption(IBMMQConstants.QUEUE_MANAGER_NAME)
                    .getValue());
            connectionFactory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
            int clientReconnectTimeout = Integer.parseInt(optionHolder.validateAndGetStaticValue(
                    IBMMQConstants.CLIENT_RECONNECT_TIMEOUT,
                    IBMMQConstants.DEFAULT_CLIENT_RECONNECTION_TIMEOUT));
            connectionFactory.setClientReconnectTimeout(clientReconnectTimeout);
            scheduledExecutorService = siddhiAppContext.getScheduledExecutorService();
        } catch (JMSException e) {
            throw new IBMMQSourceAdaptorRuntimeException("Error while initializing IBM MQ source: " + optionHolder.
                    validateAndGetOption(IBMMQConstants.DESTINATION_NAME).getValue() + ", " + e.getMessage(), e);
        }

        serviceDeploymentInfo = new ServiceDeploymentInfo(connectionFactory.getPort(),
                ibmMessageConsumerBean.isSecured());

        return null;
    }

    @Override
    public void connect(ConnectionCallback connectionCallback, State state) throws ConnectionUnavailableException {
        ibmMessageConsumerGroup = new IBMMessageConsumerGroup(scheduledExecutorService, connectionFactory,
                ibmMessageConsumerBean, siddhiAppContext.getName(), new ConnectionRetryHandler(connectionCallback));
        ibmMessageConsumerGroup.run(sourceEventListener);
    }

    @Override
    public void disconnect() {
        if (ibmMessageConsumerGroup != null) {
            ibmMessageConsumerGroup.shutdown();
            logger.info("IBM MQ source disconnected for queue '" + ibmMessageConsumerBean.getQueueName() + "'");
        }
    }

    @Override
    public void destroy() {
        ibmMessageConsumerGroup = null;
        scheduledExecutorService.shutdown();
    }

    @Override
    public void pause() {
        if (ibmMessageConsumerGroup != null) {
            ibmMessageConsumerGroup.pause();
            logger.info("IBM MQ source paused for queue '" + ibmMessageConsumerBean.getQueueName() + "'");
        }
    }

    @Override
    public void resume() {
        if (ibmMessageConsumerGroup != null) {
            ibmMessageConsumerGroup.resume();
            if (logger.isDebugEnabled()) {
                logger.debug("IBM MQ source resumed for queue '" + ibmMessageConsumerBean.getQueueName() + "'");
            }
        }
    }

    @Override
    public Class[] getOutputEventClasses() {
        return new Class[]{String.class, Map.class, ByteBuffer.class};
    }

    @Override
    protected ServiceDeploymentInfo exposeServiceDeploymentInfo() {
        return null;
    }

    private Map<String, Object> generatePropertyMap(String properties) {
        Map<String, Object> propertyMap = new HashMap<>();
        String[] propertiesArray = properties.trim().split(",");
        for (String property : propertiesArray) {
            String[] propertyArray = property.trim().split(":");
            if (propertyArray.length == 2) {
                propertyMap.put(propertyArray[0], propertyArray[1]);
            } else {
                throw new IBMMQSinkAdaptorRuntimeException("Error occurred while creating the property map. " +
                        "Properties should be provided as key value pairs in '" + siddhiAppContext.getName() +
                        "' source");
            }
        }
        return propertyMap;
    }
}
