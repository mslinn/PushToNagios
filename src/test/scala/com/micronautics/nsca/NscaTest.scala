package com.micronautics.nsca

import org.scalatest.{BeforeAndAfterAll, WordSpec}
import org.scalatest.matchers.MustMatchers
import Nsca._

/**
 * @author Mike Slinn
 */
class NscaTest extends WordSpec with BeforeAndAfterAll with MustMatchers {

  override protected def beforeAll: Unit = { }

  override protected def afterAll(): Unit = { }

  "Nsca constructor" must {
    "be called explicitly" in {
      val nsca = new Nsca("localhost", 5667, "domainBus")
      nsca.send(NAGIOS_UNKNOWN, "What's going on?")
      Thread.sleep(10000)
      nsca.send(NAGIOS_CRITICAL, "Test critical message")
      Thread.sleep(10000)
      nsca.send(NAGIOS_WARN, "Test warning message")
      Thread.sleep(10000)
      nsca.send(NAGIOS_OK, "Everything is peachy-keen")
    }

    "load properties file" in {
      val nsca = new Nsca("nsca_send_clear.properties");
      nsca.send(NAGIOS_UNKNOWN, "What's going on?")
      Thread.sleep(10000)
      nsca.send(NAGIOS_CRITICAL, "Test critical message")
      Thread.sleep(10000)
      nsca.send(NAGIOS_WARN, "Test warning message")
      Thread.sleep(10000)
      nsca.send(NAGIOS_OK, "Everything is peachy-keen")
    }
  }
}
