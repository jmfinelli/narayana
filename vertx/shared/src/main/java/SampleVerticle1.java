/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */
import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.AtomicAction;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.shareddata.LocalMap;
import org.jboss.stm.Container;

import java.util.logging.Level;
import java.util.logging.Logger;

public class SampleVerticle1 extends AbstractVerticle {
    private static final Logger LOGGER = Logger.getLogger(String.valueOf(SampleVerticle1.class));

  public void start()
  {
      LocalMap<String, String> map = vertx.sharedData().getLocalMap("demo.mymap");
      Container<Sample> theContainer = new Container<Sample>("Demo", Container.TYPE.PERSISTENT, Container.MODEL.SHARED);
      String uidName = map.get(ClientVerticle.LEADER);
      Sample obj1 = theContainer.clone(new SampleLockable(10), new Uid(uidName));
      AtomicAction A = new AtomicAction();
      int value = -1;
      int initialValue = -1;
      boolean shouldCommit = true;

      A.begin();

      try
      {
	  initialValue = obj1.value();

	  obj1.increment();

	  value = obj1.value();
      }
      catch (final Throwable ex)
      {
	  LOGGER.log(Level.WARNING, ex.getMessage(), ex);

	  shouldCommit = false;
      }

      if (shouldCommit)
	  A.commit();
      else
      {
	  A.abort();
	  value = -1;
      }

      System.err.println("SampleVerticle1 initialised state with: "+value);

      if (value == initialValue + 1)
	  System.err.println("SampleVerticle1 SUCCEEDED!");
      else
	  System.err.println("SampleVerticle1 FAILED!");
  }
}
