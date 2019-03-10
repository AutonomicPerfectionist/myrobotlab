package org.myrobotlab.test;

import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.service.Runtime;
import org.slf4j.Logger;

public class AbstractTest {

  public final static Logger log = LoggerFactory.getLogger(AbstractTest.class);
  /**
   * cached internet test value for tests
   */
  static Boolean hasInternet = null;

  static transient Set<Thread> threadSetStart = null;

  static public void sleep(int sleepMs) {
    try {
      Thread.sleep(sleepMs);
    } catch (InterruptedException e) {
      // don't care
    }
  }

  static public boolean isHeadless() {
    return Runtime.isHeadless();
  }
  
  static public boolean isVirtual() {
    boolean isVirtual = true;
    String isVirtualProp = System.getProperty("junit.isVirtual");
    
    if (isVirtualProp != null) {
      isVirtual = Boolean.parseBoolean(isVirtualProp);
    }     
    return isVirtual;
  }

  static public boolean hasInternet() {
    if (hasInternet == null) {
      hasInternet = Runtime.hasInternet();
    }
    return hasInternet;
  }

  static private boolean logTestHeader = true;

  @Before
  public void setUp() throws Exception {
  }

  @After
  public void tearDown() throws Exception {
  }

  public void testFunction() {
    log.info("tested testFunction");
  }

  // super globals - probably better not to use the mixin - but just initialize
  // statics in the
  // constructor of the AbstractTest
  @BeforeClass
  public static void setUpAbstractTest() throws Exception {
    String junitLogLevel = System.getProperty("junit.logLevel");
    if (junitLogLevel != null) {
      Runtime.setLogLevel(junitLogLevel);
    } else {
      Runtime.setLogLevel("warn"); // error instead ?
    }

    log.info("setUpAbstractTest");
    if (threadSetStart == null) {
      threadSetStart = Thread.getAllStackTraces().keySet();
    }
  }

  @AfterClass
  public static void tearDownAbstractTest() throws Exception {
    log.warn("tearDownAbstractTest");

    // services to be
    String[] services = Runtime.getServiceNames();
    for (String service : services) {
      if (!"runtime".equals(service)) {
        log.warn("service {} left in registry - releasing", service);
        Runtime.releaseService(service);
      }
    }

    // check threads - kill stragglers
    Set<Thread> stragglers = new HashSet<Thread>();
    Set<Thread> threadSetEnd = Thread.getAllStackTraces().keySet();
    for (Thread thread : threadSetEnd) {
      if (!threadSetStart.contains(thread)) {
        log.warn("thread {} marked as straggler - to be killed", thread.getName());
      }
    }
    log.warn("=========== fisnished test ===========");
  }

  public AbstractTest() {
    if (logTestHeader) {
      log.warn("=========== starting test {} ===========", this.getClass().getSimpleName());
    }
  }

  public static void main(String[] args) {
    try {
      AbstractTest test = new AbstractTest();
      // LoggingFactory.init("INFO");

      ChaosMonkey.giveToMonkey(test, "testFunction");
      ChaosMonkey.giveToMonkey(test, "testFunction");
      ChaosMonkey.giveToMonkey(test, "testFunction");
      ChaosMonkey.startMonkeys();
      ChaosMonkey.monkeyReport();
      log.info("here");

    } catch (Exception e) {
      log.error("main threw", e);
    }
  }

}
