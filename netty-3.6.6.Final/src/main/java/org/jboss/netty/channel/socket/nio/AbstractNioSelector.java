/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.jboss.netty.channel.socket.nio;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.ThreadNameDeterminer;
import org.jboss.netty.util.ThreadRenamingRunnable;
import org.jboss.netty.util.internal.DeadLockProofWorker;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ConcurrentModificationException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

abstract class AbstractNioSelector implements NioSelector {

    private static final AtomicInteger nextId = new AtomicInteger();

    private final int id = nextId.incrementAndGet();

    /**
     * Internal Netty logger.
     */
    protected static final InternalLogger logger = InternalLoggerFactory
            .getInstance(AbstractNioSelector.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * executor 就是在创建具体的channel factory时给定的 bossExecutor或workerExecutor
     * Executor used to execute {@link Runnable}s such as channel registration
     * task.
     */
    private final Executor executor;

    /**
     * If this worker has been started thread will be a reference to the thread
     * used when starting. i.e. the current thread when the run method is executed.
     */
    protected volatile Thread thread;

    /**
     * The NIO {@link Selector}.
     */
    protected volatile Selector selector;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     */
    protected final AtomicBoolean wakenUp = new AtomicBoolean();

    private final Queue<Runnable> taskQueue = new ConcurrentLinkedQueue<Runnable>();

    private volatile int cancelledKeys; // should use AtomicInteger but we just need approximation

    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private volatile boolean shutdown;

    AbstractNioSelector(Executor executor) {
        this(executor, null);
    }

    AbstractNioSelector(Executor executor, ThreadNameDeterminer determiner) {
        this.executor = executor;
        openSelector(determiner);
    }

    public void register(Channel channel, ChannelFuture future) {
        Runnable task = createRegisterTask(channel, future);
        registerTask(task);
    }

    protected final void registerTask(Runnable task) {
        taskQueue.add(task);

        Selector selector = this.selector;

        if (selector != null) {
        	// selector.select()方法
        	// 执行一次阻塞的选择操作，且仅在发生如下三种情况中的任何一种后返回：
    		// 1.至少有一个通道被选择
    		// 2.selector.wakeup()方法被调用
    		// 3.当前线程被中断
        	
        	/*
        	 * Added by Simon
        	 * 在这里当wakenUp为false时，如果有IO thread因为调用selector.select(...)方法而阻塞且未被其他线程唤醒的话，
        	 * 刚接受(accepted)的socket channel在selector那儿的注册工作就没办法进行了，导致无法进行后续的读写操作。
        	 * 请看NioServerBoss类bind(...)和registerAcceptedChannel(...)方法
        	 */
            if (wakenUp.compareAndSet(false, true)) {
            	// 为什么要在这里唤醒selector呢？
            	// 因为在当前对象的run()方法中先调用了selector.select()方法，
            	// 再调用了processTaskQueue()方法，如果IO thread因为调用select()方法而阻塞，
            	// 那就不会及时处理新注册的任务了
                selector.wakeup();
            }
        } else {
            if (taskQueue.remove(task)) {
                // the selector was null this means the Worker has already been shutdown.
                throw new RejectedExecutionException("Worker has already been shutdown");
            }
        }
    }

    protected final boolean isIoThread() {
        return Thread.currentThread() == thread;
    }

    public void rebuildSelector() {
        if (!isIoThread()) {
            taskQueue.add(new Runnable() {
                public void run() {
                    rebuildSelector();
                }
            });
            return;
        }

        final Selector oldSelector = selector;
        final Selector newSelector;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelector = Selector.open();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        for (;;) {
            try {
                for (SelectionKey key: oldSelector.keys()) {
                    try {
                        if (key.channel().keyFor(newSelector) != null) {
                            continue;
                        }

                        int interestOps = key.interestOps();
                        key.cancel();
                        key.channel().register(newSelector, interestOps, key.attachment());
                        nChannels ++;
                    } catch (Exception e) {
                        logger.warn("Failed to re-register a Channel to the new Selector,", e);
                        close(key);
                    }
                }
            } catch (ConcurrentModificationException e) {
                // Probably due to concurrent modification of the key set.
                continue;
            }

            break;
        }

        selector = newSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        logger.info("Migrated " + nChannels + " channel(s) to the new Selector,");
    }

    public void run() {
        thread = Thread.currentThread();

        int selectReturnsImmediately = 0;
        Selector selector = this.selector;

        if (selector == null) {
            return;
        }
        // use 80% of the timeout for measure
        final long minSelectTimeout = SelectorUtil.SELECT_TIMEOUT_NANOS * 80 / 100;
        // 
        boolean wakenupFromLoop = false;
        for (;;) {
            wakenUp.set(false);

            try {
                long beforeSelect = System.nanoTime();
                
                /*
                 * Added by Simon
                 * selector.select(...):
                 * This method performs a blocking selection operation.
                 * It returns only after at least one channel is selected, this selector's wakeup method is invoked,
                 * the current thread is interrupted, or the given timeout period expires, whichever comes first.
                 * @return  The number of keys, possibly zero, whose ready-operation sets were updated
                 */
                int selected = select(selector);
                
                // Added by Simon
                // selected为0有三种可能：selector.wakeup()方法被调用了、当前线程被中断了、给定的暂停时间到期了
                // 上面这句注释有问题，从api doc上看，没有明确说明合适返回0，上面的信息来自哪里？
                // 首先，selected为0表明select操作没有选取到任何可操作的channel，那么下面这个if分支就是在处理非正常情况
                if (SelectorUtil.EPOLL_BUG_WORKAROUND && selected == 0 && !wakenupFromLoop && !wakenUp.get()) {
                    long timeBlocked = System.nanoTime() - beforeSelect;

                    if (timeBlocked < minSelectTimeout) {
                        // 标示selector中是否有已关闭的通道
                        boolean notConnected = false;
                        // loop over all keys as the selector may was unblocked because of a closed channel
                        // 遍历所有的key，因为selector可能因为一个已经关闭了的channel而解除了阻塞。
                        // 因此，我们需要遍历所有的key，处理那些已关闭的通道
                        for (SelectionKey key: selector.keys()) {
                            SelectableChannel ch = key.channel();
                            try {
                                if (ch instanceof DatagramChannel && !((DatagramChannel) ch).isConnected() ||
                                        ch instanceof SocketChannel && !((SocketChannel) ch).isConnected()) {
                                    notConnected = true;
                                    // cancel the key just to be on the safe side
                                    key.cancel();
                                }
                            } catch (CancelledKeyException e) {
                                // ignore
                            }
                        }
                        if (notConnected) {
                            selectReturnsImmediately = 0;
                        } else {
                            // returned before the minSelectTimeout elapsed with nothing select.
                            // this may be the cause of the jdk epoll(..) bug, so increment the counter
                            // which we use later to see if its really the jdk bug.
                            selectReturnsImmediately ++;
                        }
                    } else {
                        selectReturnsImmediately = 0;
                    }

                    /*
                     * 如果在 minSelectTimeout 到期前尝试了1024次select操作仍然没有选取到任何东西，
                     * 那我们可能碰到了jdk epoll bug，此时需要重建selector对象等等
                     */
                    if (selectReturnsImmediately == 1024) {
                        // The selector returned immediately for 10 times in a row,
                        // so recreate one selector as it seems like we hit the
                        // famous epoll(..) jdk bug.
                        rebuildSelector();
                        selector = this.selector;
                        selectReturnsImmediately = 0;
                        wakenupFromLoop = false;
                        // try to select again
                        continue;
                    }
                } else {
                    // reset counter
                    selectReturnsImmediately = 0;
                }

                // 'wakenUp.compareAndSet(false, true)' is always evaluated
                // before calling 'selector.wakeup()' to reduce the wake-up
                // overhead. (Selector.wakeup() is an expensive operation.)
                //
                // However, there is a race condition in this approach.
                // The race condition is triggered when 'wakenUp' is set to
                // true too early.
                //
                // 'wakenUp' is set to true too early if:
                // 1) Selector is waken up between 'wakenUp.set(false)' and
                //    'selector.select(...)'. (BAD)
                // 2) Selector is waken up between 'selector.select(...)' and
                //    'if (wakenUp.get()) { ... }'. (OK)
                //
                // In the first case, 'wakenUp' is set to true and the
                // following 'selector.select(...)' will wake up immediately.
                // Until 'wakenUp' is set to false again in the next round,
                // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                // any attempt to wake up the Selector will fail, too, causing
                // the following 'selector.select(...)' call to block
                // unnecessarily.
                //
                // To fix this problem, we wake up the selector again if wakenUp
                // is true immediately after selector.select(...).
                // It is inefficient in that it wakes up the selector for both
                // the first case (BAD - wake-up required) and the second case
                // (OK - no wake-up required).

                if (wakenUp.get()) {
                    wakenupFromLoop = true;
                    selector.wakeup();
                } else {
                    wakenupFromLoop = false;
                }

                cancelledKeys = 0;
                processTaskQueue();
                // processTaskQueue()可能会rebuild selector，所以此时需要重新赋值
                selector = this.selector; // processTaskQueue() can call rebuildSelector()

                if (shutdown) {
                    this.selector = null;

                    // process one time again
                    processTaskQueue();

                    for (SelectionKey k: selector.keys()) {
                        close(k);
                    }

                    try {
                        selector.close();
                    } catch (IOException e) {
                        logger.warn(
                                "Failed to close a selector.", e);
                    }
                    shutdownLatch.countDown();
                    break;
                } else {
                    process(selector);
                }
            } catch (Throwable t) {
                logger.warn(
                        "Unexpected exception in the selector loop.", t);

                // Prevent possible consecutive immediate failures that lead to
                // excessive CPU consumption.
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // Ignore.
                }
            }
        }
    }

    /**
     * Start the {@link AbstractNioWorker} and return the {@link Selector} that will be used for
     * the {@link AbstractNioChannel}'s when they get registered
     */
    private void openSelector(ThreadNameDeterminer determiner) {
        try {
            selector = Selector.open();
        } catch (Throwable t) {
            throw new ChannelException("Failed to create a selector.", t);
        }

        // Start the worker thread with the new Selector.
        boolean success = false;
        try {
        	// 看看DeadLockProofWorker.start(...)和当前类的子类(即具体的Boss和Worker)中newThreadRenamingRunnable()
        	// 方法的实现就知道Boss和Worker是如何启动了
            DeadLockProofWorker.start(executor, newThreadRenamingRunnable(id, determiner));
            success = true;
        } finally {
            if (!success) {
                // Release the Selector if the execution fails.
                try {
                    selector.close();
                } catch (Throwable t) {
                    logger.warn("Failed to close a selector.", t);
                }
                selector = null;
                // The method will return to the caller at this point.
            }
        }
        assert selector != null && selector.isOpen();
    }

    private void processTaskQueue() {
        for (;;) {
            final Runnable task = taskQueue.poll();
            if (task == null) {
                break;
            }
            task.run();
            try {
                cleanUpCancelledKeys();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    protected final void increaseCancelledKeys() {
        cancelledKeys ++;
    }

    protected final boolean cleanUpCancelledKeys() throws IOException {
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            selector.selectNow();
            return true;
        }
        return false;
    }

    public void shutdown() {
        if (isIoThread()) {
            throw new IllegalStateException("Must not be called from a I/O-Thread to prevent deadlocks!");
        }

        Selector selector = this.selector;
        shutdown = true;
        if (selector != null) {
            selector.wakeup();
        }
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            logger.error("Interrupted while wait for resources to be released #" + id);
            Thread.currentThread().interrupt();
        }
    }

    protected abstract void process(Selector selector) throws IOException;

    protected int select(Selector selector) throws IOException {
        return SelectorUtil.select(selector);
    }

    protected abstract void close(SelectionKey k);

    protected abstract ThreadRenamingRunnable newThreadRenamingRunnable(int id, ThreadNameDeterminer determiner);

    protected abstract Runnable createRegisterTask(Channel channel, ChannelFuture future);
}
