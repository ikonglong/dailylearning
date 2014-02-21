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
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ConnectTimeoutException;
import org.jboss.netty.util.ThreadNameDeterminer;
import org.jboss.netty.util.ThreadRenamingRunnable;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.Timer;
import org.jboss.netty.util.TimerTask;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.jboss.netty.channel.Channels.*;

/**
 * {@link Boss} implementation that handles the  connection attempts of clients
 */
public final class NioClientBoss extends AbstractNioSelector implements Boss {

    private final TimerTask wakeupTask = new TimerTask() {
        public void run(Timeout timeout) throws Exception {
            // This is needed to prevent a possible race that can lead to a NPE
            // when the selector is closed before this is run
            //
            // See https://github.com/netty/netty/issues/685
            Selector selector = NioClientBoss.this.selector;

            if (selector != null) {
                if (wakenUp.compareAndSet(false, true)) {
                    selector.wakeup();
                }
            }
        }
    };

    private final Timer timer;

    NioClientBoss(Executor bossExecutor, Timer timer, ThreadNameDeterminer determiner) {
        super(bossExecutor, determiner);
        this.timer = timer;
    }

    @Override
    protected ThreadRenamingRunnable newThreadRenamingRunnable(int id, ThreadNameDeterminer determiner) {
        return new ThreadRenamingRunnable(this, "New I/O boss #" + id, determiner);
    }

    @Override
    protected Runnable createRegisterTask(Channel channel, ChannelFuture future) {
        return new RegisterTask(this, (NioClientSocketChannel) channel);
    }

    @Override
    protected void process(Selector selector) {
        processSelectedKeys(selector.selectedKeys());

        // Handle connection timeout every 10 milliseconds approximately.
        long currentTimeNanos = System.nanoTime();
        processConnectTimeout(selector.keys(), currentTimeNanos);
    }

    private void processSelectedKeys(Set<SelectionKey> selectedKeys) {

        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }
        for (Iterator<SelectionKey> i = selectedKeys.iterator(); i.hasNext();) {
            SelectionKey k = i.next();
            i.remove();

            if (!k.isValid()) {
                close(k);
                continue;
            }

            try {
                if (k.isConnectable()) {
                    connect(k);
                }
            } catch (Throwable t) {
                NioClientSocketChannel ch = (NioClientSocketChannel) k.attachment();
                ch.connectFuture.setFailure(t);
                fireExceptionCaught(ch, t);
                k.cancel(); // Some JDK implementations run into an infinite loop without this.
                ch.worker.close(ch, succeededFuture(ch));
            }
        }
    }

    private static void processConnectTimeout(Set<SelectionKey> keys, long currentTimeNanos) {
        ConnectException cause = null;
        for (SelectionKey k: keys) {
            if (!k.isValid()) {
                // Comment the close call again as it gave us major problems
                // with ClosedChannelExceptions.
                //
                // See:
                // * https://github.com/netty/netty/issues/142
                // * https://github.com/netty/netty/issues/138
                //
                // close(k);
                continue;
            }

            NioClientSocketChannel ch = (NioClientSocketChannel) k.attachment();
            if (ch.connectDeadlineNanos > 0 &&
                    currentTimeNanos >= ch.connectDeadlineNanos) {

                if (cause == null) {
                    cause = new ConnectTimeoutException("connection timed out: " + ch.requestedRemoteAddress);
                }

                ch.connectFuture.setFailure(cause);
                fireExceptionCaught(ch, cause);
                ch.worker.close(ch, succeededFuture(ch));
            }
        }
    }

    /**
     * 并非实际建立socket连接的方法。
     * 完成SelectionKey对应的channel的connect操作，同时检查连接状态。
     * 当前对象持有的selector中注册的都是在NioClientSocketPipelineSink.connect(...)中
     * 未能立即成功建立连接的channel。如果已连接成功，就将其从selector中移除，并交给NioWorker去处理。
     *
     * @param k
     * @throws IOException
     */
    private static void connect(SelectionKey k) throws IOException {
        NioClientSocketChannel ch = (NioClientSocketChannel) k.attachment();
        try {
            if (ch.channel.finishConnect()) {
                k.cancel();
                if (ch.timoutTimer != null) {
                    ch.timoutTimer.cancel();
                }
                // 由NioWorker将channel注册至其持有的selector中，并监听用户感兴趣的操作
                ch.worker.register(ch, ch.connectFuture);
            }
        } catch (ConnectException e) {
            ConnectException newE = new ConnectException(e.getMessage() + ": " + ch.requestedRemoteAddress);
            newE.setStackTrace(e.getStackTrace());
            throw newE;
        }
    }

    @Override
    protected void close(SelectionKey k) {
        NioClientSocketChannel ch = (NioClientSocketChannel) k.attachment();
        ch.worker.close(ch, succeededFuture(ch));
    }

    private final class RegisterTask implements Runnable {
        private final NioClientBoss boss;
        private final NioClientSocketChannel channel;

        RegisterTask(NioClientBoss boss, NioClientSocketChannel channel) {
            this.boss = boss;
            this.channel = channel;
        }

        public void run() {
            /*
             * 如果在NioClientSocketPipelineSink.connect(...)中与服务端连接建立失败，
             * 就会在NioClientBoss.run()所在的IoThread中执行该任务
             */
            int timeout = channel.getConfig().getConnectTimeoutMillis();
            if (timeout > 0) {
                if (!channel.isConnected()) {
                    channel.timoutTimer = timer.newTimeout(wakeupTask,
                            timeout, TimeUnit.MILLISECONDS);
                }
            }
            try {
                channel.channel.register(
                        boss.selector, SelectionKey.OP_CONNECT, channel);
            } catch (ClosedChannelException e) {
                channel.worker.close(channel, succeededFuture(channel));
            }

            int connectTimeout = channel.getConfig().getConnectTimeoutMillis();
            if (connectTimeout > 0) {
                channel.connectDeadlineNanos = System.nanoTime() + connectTimeout * 1000000L;
            }
        }
    }
}
