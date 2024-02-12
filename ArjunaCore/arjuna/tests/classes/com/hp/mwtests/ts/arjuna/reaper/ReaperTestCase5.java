package com.hp.mwtests.ts.arjuna.reaper;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.coordinator.Reapable;
import com.arjuna.ats.arjuna.coordinator.TransactionReaper;
import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.fail;

@RunWith(BMUnitRunner.class)
@BMScript("reaper")
@BMUnitConfig(debug = true)
public class ReaperTestCase5 extends ReaperTestCaseControl {

    private static int _timeout = 5;

    private Reapable reapable = new SimpleReapable();

    private volatile long beforeTerminate;
    private volatile long afterWait;

    @Test
    public void testWithRendezvous() {
        enableRendezvous("controlledshutdown", false);
        test();
    }

    @Test
    public void testWithoutRendezvous() {
        test();
    }

    private void test() {
        TransactionReaper transactionReaper = TransactionReaper.transactionReaper();
        transactionReaper.insert(reapable, _timeout);

        AtomicBoolean terminated = new AtomicBoolean(false);
        new Thread(() -> {
            // The shutdown is from another thread
            this.beforeTerminate = System.currentTimeMillis();
            transactionReaper.terminate(false);
            synchronized (terminated) {
                terminated.set(true);
                terminated.notify();
            }
        }).start();
        // This needs to be after the thread above has started so that the other thread can start a shutdown
        transactionReaper.remove(reapable);

        synchronized (terminated) {
            if (!terminated.get()) {
                try {
                    terminated.wait();
                    this.afterWait = System.currentTimeMillis();
                    long duration = afterWait - beforeTerminate;

                    // The duration (_timeout * 1000) + 1000 is worked out by adding 1s to _timeout. This is needed
                    // as ReaperThread needs to wait the first timeout and then process the other transactions.
                    Assert.assertTrue(String.format("The timeout duration was %s!", duration), duration < ((_timeout * 1000) + 1000));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        if (!terminated.get()) {
            fail("TreansactionReaper terminate blocked");
        }
    }

    private class SimpleReapable implements Reapable {
        private Uid uid = new Uid();

        @Override
        public boolean running() {
            return true;
        }

        @Override
        public boolean preventCommit() {
            return false;
        }

        @Override
        public int cancel() {
            return 0;
        }

        @Override
        public Uid get_uid() {
            return uid;
        }
    }
}
