package test.endtoend.auctionsniper;


import auctionsniper.ui.MainWindow;
import com.objogate.wl.swing.AWTEventQueueProber;
import com.objogate.wl.swing.driver.JFrameDriver;
import com.objogate.wl.swing.driver.JLabelDriver;
import com.objogate.wl.swing.gesture.GesturePerformer;

import static org.hamcrest.Matchers.equalTo;

/**
 * Driver负责对ui中的各个组件、控件进行断言
 */
public class AuctionSniperDriver extends JFrameDriver {
    public AuctionSniperDriver(int timeoutMillis) {
        super(new GesturePerformer(),
                JFrameDriver.topLevelFrame(
                        named(MainWindow.MAIN_WINDOW_NAME),
                        showingOnScreen()),
                new AWTEventQueueProber(timeoutMillis, 100));
    }

    /**
     * 断言显示sniper状态的标签控件文本是否为给定的值
     * @param statusText
     */
    public void showsSniperStatus(String statusText) {
        new JLabelDriver(this, named(MainWindow.SNIPER_STATUS_NAME))
                .hasText(equalTo(statusText));
    }
}
