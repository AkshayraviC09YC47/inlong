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

package org.apache.inlong.dataproxy.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.SocketAddress;
import io.netty.channel.Channel;

public class AddressUtils {

    private static final Logger logger = LoggerFactory.getLogger(AddressUtils.class);

    public static String getChannelLocalIP(Channel channel) {
        return getChannelIP(channel, true);
    }

    public static String getChannelRemoteIP(Channel channel) {
        return getChannelIP(channel, false);
    }

    private static String getChannelIP(Channel channel, boolean isLocal) {
        if (channel == null) {
            return null;
        }
        SocketAddress address = isLocal ? channel.localAddress() : channel.remoteAddress();
        if (address == null) {
            return null;
        }
        String strAddrIP = address.toString();
        try {
            strAddrIP = strAddrIP.substring(1, strAddrIP.indexOf(':'));
            return strAddrIP;
        } catch (Exception ee) {
            if (isLocal) {
                logger.warn("Fail to get the local IP, localAddress = {}", address);
            } else {
                logger.warn("Fail to get the remote IP, remoteAddress = {}", address);
            }
            return null;
        }
    }

}
