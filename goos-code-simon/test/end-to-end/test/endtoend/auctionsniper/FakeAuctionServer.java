package test.endtoend.auctionsniper;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.Message;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class FakeAuctionServer {
    public static final String ITEM_ID_AS_LOGIN = "auction-%s";
    public static final String AUCTION_RESOURCE = "Auction";
    public static final String XMPP_HOSTNAME = "localhost";
    public static final String AUCTION_PASSWORD = "auction";

    private final String itemId;
    private final XMPPConnection connection;
    private Chat currentChat;

    private final SingleMessageListener messageListener = new SingleMessageListener();

    public FakeAuctionServer(String itemId) {
        this.itemId = itemId;
        this.connection = new XMPPConnection(XMPP_HOSTNAME);
    }

    public void startSellingItem() throws XMPPException {
        // 建立Auction与Message Broker即OpenFire之间的连接
        connection.connect();
        // login(username, password, resource)
        connection.login(String.format(ITEM_ID_AS_LOGIN, itemId),
                AUCTION_PASSWORD, AUCTION_RESOURCE);
        connection.getChatManager().addChatListener(
                new ChatManagerListener() {
                    @Override
                    public void chatCreated(Chat chat, boolean createdLocally) {
                        // 持有新创建的Chat对象
                        currentChat = chat;
                        chat.addMessageListener(messageListener);
                    }
                }
        );
    }

    public String getItemId() {
        return itemId;
    }

    /**
     * 这是个断言方法。
     * 断言方法命名使用陈述或直陈句式
     * @throws InterruptedException
     */
    public void hasReceivedJoinRequestFromSniper() throws InterruptedException {
        messageListener.receivesAMessage();
    }

    /**
     * 触发Chat关闭事件。
     * 触发事件的方法名以祈使或命令句式命名。
     * @throws XMPPException
     */
    public void announceClosed() throws XMPPException {
        currentChat.sendMessage(new Message());
    }

    public void stop() {
        connection.disconnect();
    }

    /**
     * A helper class that accepts and processes messages from the sniper.
     */
    public class SingleMessageListener implements MessageListener {
        /**
         * Coordinate between the thread that runs the test and
         * the Smack thread that feeds messages to the listener.
         */
        private final ArrayBlockingQueue<Message> messages =
                new ArrayBlockingQueue<Message>(1);

        @Override
        public void processMessage(Chat chat, Message message) {
            messages.add(message);
        }

        /**
         * Process message
         * @throws InterruptedException
         */
        public void receivesAMessage() throws InterruptedException {
            assertThat("Message", messages.poll(5, TimeUnit.SECONDS), is(notNullValue()));
        }
    }
}
