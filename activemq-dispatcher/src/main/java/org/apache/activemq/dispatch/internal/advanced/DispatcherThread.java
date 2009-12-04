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
package org.apache.activemq.dispatch.internal.advanced;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.dispatch.DispatchSystem;
import org.apache.activemq.dispatch.DispatchSystem.DispatchQueuePriority;
import org.apache.activemq.util.Mapper;
import org.apache.activemq.util.PriorityLinkedList;
import org.apache.activemq.util.TimerHeap;
import org.apache.activemq.util.list.LinkedNode;
import org.apache.activemq.util.list.LinkedNodeList;

public class DispatcherThread implements Runnable {

    private final ThreadDispatchQueue dispatchQueues[];
    
    static final boolean DEBUG = false;
    private Thread thread;
    protected boolean running = false;
    private boolean threaded = false;
    protected final int MAX_USER_PRIORITY;
    protected final HashSet<DispatchContext> contexts = new HashSet<DispatchContext>();

    // Set if this dispatcher is part of a dispatch pool:
    protected final AdvancedDispatchSPI spi;

    // The local dispatch queue:
    protected final PriorityLinkedList<DispatchContext> priorityQueue;

    // Dispatch queue for requests from other threads:
    private final LinkedNodeList<ForeignEvent>[] foreignQueue;
    private static final int[] TOGGLE = new int[] { 1, 0 };
    private int foreignToggle = 0;

    // Timed Execution List
    protected final TimerHeap<Runnable> timerHeap = new TimerHeap<Runnable>() {
        @Override
        protected final void execute(Runnable ready) {
            ready.run();
        }
    };

    protected final String name;
    private final AtomicBoolean foreignAvailable = new AtomicBoolean(false);
    private final Semaphore foreignPermits = new Semaphore(0);

    private final Mapper<Integer, DispatchContext> PRIORITY_MAPPER = new Mapper<Integer, DispatchContext>() {
        public Integer map(DispatchContext element) {
            return element.listPrio;
        }
    };

    protected DispatcherThread(AdvancedDispatchSPI spi, String name, int priorities) {
        this.name = name;
        
        this.dispatchQueues = new ThreadDispatchQueue[3];
        for (int i = 0; i < 3; i++) {
            dispatchQueues[i] = new ThreadDispatchQueue(this, DispatchQueuePriority.values()[i]);
        }

        MAX_USER_PRIORITY = priorities - 1;
        priorityQueue = new PriorityLinkedList<DispatchContext>(MAX_USER_PRIORITY + 1, PRIORITY_MAPPER);
        foreignQueue = createForeignEventQueue();
        for (int i = 0; i < 2; i++) {
            foreignQueue[i] = new LinkedNodeList<ForeignEvent>();
        }
        this.spi = spi;
    }

    @SuppressWarnings("unchecked")
    private LinkedNodeList<ForeignEvent>[] createForeignEventQueue() {
        return new LinkedNodeList[2];
    }

    protected abstract class ForeignEvent extends LinkedNode<ForeignEvent> {
        public abstract void execute();

        final void addToList() {
            synchronized (foreignQueue) {
                if (!this.isLinked()) {
                    foreignQueue[foreignToggle].addLast(this);
                    if (!foreignAvailable.getAndSet(true)) {
                        wakeup();
                    }
                }
            }
        }
    }

    public boolean isThreaded() {
        return threaded;
    }

    public void setThreaded(boolean threaded) {
        this.threaded = threaded;
    }

    public int getDispatchPriorities() {
        return MAX_USER_PRIORITY;
    }

    class UpdateEvent extends ForeignEvent {
        private final DispatchContext pdc;

        UpdateEvent(DispatchContext pdc) {
            this.pdc = pdc;
        }

        // Can only be called by the owner of this dispatch context:
        public void execute() {
            pdc.processForeignUpdates();
        }
    }

    public DispatchContext register(Runnable runnable, String name) {
        return new DispatchContext(this, runnable, true, name);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.activemq.dispatch.IDispatcher#start()
     */
    public synchronized final void start() {
        if (thread == null) {
            running = true;
            thread = new Thread(this, name);
            thread.start();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.activemq.dispatch.IDispatcher#shutdown()
     */
    public void shutdown() throws InterruptedException {
        Thread joinThread = shutdown(new AtomicInteger(1), null);
        if (joinThread != null) {
            // thread.interrupt();
            joinThread.join();
        }
    }
    
    public Thread shutdown(final AtomicInteger shutdownCountDown, final Runnable onShutdown) {
        synchronized (this) {
            if (thread != null) {
                dispatchInternal(new Runnable() {
                    public void run() {
                        running = false;
                        if( shutdownCountDown.decrementAndGet()==0 && onShutdown!=null) {
                            onShutdown.run();
                        }
                    }
                }, MAX_USER_PRIORITY + 1);
                Thread rc = thread;
                thread = null;
                return rc;
            } else {
                if( shutdownCountDown.decrementAndGet()==0 && onShutdown!=null) {
                    onShutdown.run();
                }
            }
            return null;
        }
    }

    protected void cleanup() {
        ArrayList<DispatchContext> toClose = null;
        synchronized (this) {
            running = false;
            toClose = new ArrayList<DispatchContext>(contexts.size());
            toClose.addAll(contexts);
        }

        for (DispatchContext context : toClose) {
            context.close(false);
        }
    }

    public void run() {

        if (spi != null) {
            // Inform the dispatcher that we have started:
            spi.onDispatcherStarted((DispatcherThread) this);
        }

        DispatchContext pdc;
        try {
            while (running) {
                int counter = 0;
                // If no local work available wait for foreign work:
                while((pdc = priorityQueue.poll())!=null){
                    if( pdc.priority < dispatchQueues.length ) {
                        DispatchSystem.CURRENT_QUEUE.set(dispatchQueues[pdc.priority]);
                    }
                    
                    if (pdc.tracker != null) {
                        spi.setCurrentDispatchContext(pdc);
                    }

                    counter++;
                    pdc.run();

                    if (pdc.tracker != null) {
                        spi.setCurrentDispatchContext(null);
                    }
                }

                if( counter==0 ) {
                    waitForEvents();
                }

                // Execute delayed events:
                timerHeap.executeReadyTimers();

                // Allow subclasses to do additional work:
                dispatchHook();

                // Check for foreign dispatch requests:
                if (foreignAvailable.get()) {
                    LinkedNodeList<ForeignEvent> foreign;
                    synchronized (foreignQueue) {
                        // Swap foreign queues and drain permits;
                        foreign = foreignQueue[foreignToggle];
                        foreignToggle = TOGGLE[foreignToggle];
                        foreignAvailable.set(false);
                        foreignPermits.drainPermits();
                    }
                    while (true) {
                        ForeignEvent fe = foreign.getHead();
                        if (fe == null) {
                            break;
                        }

                        fe.unlink();
                        fe.execute();
                    }
                }
            }
        } catch (InterruptedException e) {
            return;
        } catch (Throwable thrown) {
            thrown.printStackTrace();
        } finally {
            if (spi != null) {
                spi.onDispatcherStopped((DispatcherThread) this);
            }
            cleanup();
        }
    }

    /**
     * Subclasses may override this to do do additional dispatch work:
     */
    protected void dispatchHook() throws Exception {

    }

    /**
     * Subclasses may override this to implement another mechanism for wakeup.
     * 
     * @throws Exception
     */
    protected void waitForEvents() throws Exception {
        long next = timerHeap.timeToNext(TimeUnit.NANOSECONDS);
        if (next == -1) {
            foreignPermits.acquire();
        } else if (next > 0) {
            foreignPermits.tryAcquire(next, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Subclasses may override this to provide an alternative wakeup mechanism.
     */
    protected void wakeup() {
        foreignPermits.release();
    }

    protected final void onForeignUpdate(DispatchContext context) {
        synchronized (foreignQueue) {

            ForeignEvent fe = context.updateEvent[foreignToggle];
            if (!fe.isLinked()) {
                foreignQueue[foreignToggle].addLast(fe);
                if (!foreignAvailable.getAndSet(true)) {
                    wakeup();
                }
            }
        }
    }

    protected final boolean removeDispatchContext(DispatchContext context) {
        synchronized (foreignQueue) {

            if (context.updateEvent[0].isLinked()) {
                context.updateEvent[0].unlink();
            }
            if (context.updateEvent[1].isLinked()) {
                context.updateEvent[1].unlink();
            }
        }

        if (context.isLinked()) {
            context.unlink();
            return true;
        }

        synchronized (this) {
            contexts.remove(context);
        }

        return false;
    }

    protected final boolean takeOwnership(DispatchContext context) {
        synchronized (this) {
            if (running) {
                contexts.add(context);
            } else {
                return false;
            }
        }
        return true;
    }

    //Special dispatch method that allow high priority dispatch:
    private final void dispatchInternal(Runnable runnable, int priority) {
        DispatchContext context = new DispatchContext(this, runnable, false, name);
        context.priority = priority;
        context.requestDispatch();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.activemq.dispatch.IDispatcher#dispatch(org.apache.activemq
     * .dispatch.Dispatcher.Dispatchable)
     */
    public final void dispatch(Runnable runnable, int priority) {
        DispatchContext context = new DispatchContext(this, runnable, false, name);
        context.updatePriority(priority);
        context.requestDispatch();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.activemq.dispatch.IDispatcher#createPriorityExecutor(int)
     */
    public Executor createPriorityExecutor(final int priority) {

        return new Executor() {

            public void execute(final Runnable runnable) {
                dispatch(runnable, priority);
            }
        };
    }

    public void execute(final Runnable runnable) {
        dispatch(runnable, 0);
    }
    
    public void execute(final Runnable runnable, int prio) {
        dispatch(runnable, prio);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.activemq.dispatch.IDispatcher#schedule(java.lang.Runnable,
     * long, java.util.concurrent.TimeUnit)
     */
    public void schedule(final Runnable runnable, final long delay, final TimeUnit timeUnit) {
        schedule(runnable, 0, delay, timeUnit);
    }
    
    public void schedule(final Runnable runnable, final int prio, final long delay, final TimeUnit timeUnit) {
        final Runnable wrapper = new Runnable() {
            public void run() {
                execute(runnable, prio);
            }
        };
        if (getCurrentDispatcher() == this) {
            timerHeap.addRelative(wrapper, delay, timeUnit);
        } else {
            new ForeignEvent() {
                public void execute() {
                    timerHeap.addRelative(wrapper, delay, timeUnit);
                }
            }.addToList();
        }
    }

    public String toString() {
        return name;
    }

    final DispatcherThread getCurrentDispatcher() {
        if (spi != null) {
            return (DispatcherThread) spi.getCurrentDispatcher();
        } else if (Thread.currentThread() == thread) {
            return (DispatcherThread) this;
        } else {
            return null;
        }

    }

    final DispatchContext getCurrentDispatchContext() {
        return spi.getCurrentDispatchContext();
    }

    public String getName() {
        return name;
    }

}