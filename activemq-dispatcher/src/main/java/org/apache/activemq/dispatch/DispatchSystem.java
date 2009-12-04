/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.dispatch;

import java.nio.channels.SelectableChannel;

import org.apache.activemq.dispatch.internal.simple.SimpleDispatchSPI;

/**
 * 
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class DispatchSystem {

    public static enum DispatchQueuePriority {
        HIGH,
        DEFAULT,
        LOW;
    }

    static abstract public class DispatchSPI {
        abstract public DispatchQueue getMainQueue();
        abstract public DispatchQueue getGlobalQueue(DispatchQueuePriority priority);
        abstract public DispatchQueue createQueue(String label);
        abstract public void dispatchMain();
        abstract public DispatchSource createSource(SelectableChannel channel, int interestOps, DispatchQueue queue);
    }

    public final static ThreadLocal<DispatchQueue> CURRENT_QUEUE = new ThreadLocal<DispatchQueue>();
    static public DispatchQueue getCurrentQueue() {
        return CURRENT_QUEUE.get();
    }

    private static DispatchSPI spi;
    
    private static DispatchSPI cretateDispatchSystemSPI() {
        return new SimpleDispatchSPI(Runtime.getRuntime().availableProcessors());
    }
    
    synchronized private static DispatchSPI spi() {
        if(spi==null) {
            spi = cretateDispatchSystemSPI();
        }
        return spi;
    }
    
    static DispatchQueue getMainQueue() {
        return spi().getMainQueue();
    }
    
    static public DispatchQueue getGlobalQueue(DispatchQueuePriority priority) {
        return spi().getGlobalQueue(priority);
    }
    
    static DispatchQueue createQueue(String label) {
        return spi().createQueue(label);
    }
    
    static void dispatchMain() {
        spi().dispatchMain();
    }

    static DispatchSource createSource(SelectableChannel channel, int interestOps, DispatchQueue queue) {
        return spi().createSource(channel, interestOps, queue);
    }


}
