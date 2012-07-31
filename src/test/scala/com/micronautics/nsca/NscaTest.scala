/*
 * Copyright 2012 Bookish, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License. */

package com.micronautics.nsca

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import Nsca._

/**
 * @author Mike Slinn
 */
class NscaTest extends WordSpec with MustMatchers {

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
