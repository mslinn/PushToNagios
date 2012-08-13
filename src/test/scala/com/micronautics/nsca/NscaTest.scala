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
      val nsca = new Nsca("blah", 1234, "asdf")
      expect("asdf", "")(nsca.getNscaService)
      expect(Encryption.NONE, "")(nsca.getEncryptionMethod)
      expect("blah", "")(nsca.getNscaHost)
      expect(1234, "")(nsca.getNscaPort)
    }

    "load layered config file(s)" in {
      val nsca = new Nsca()
      expect("applicationService", "")(nsca.getNscaService)
      expect(Encryption.NONE, "")(nsca.getEncryptionMethod)
      expect("farFarAway", "")(nsca.getNscaHost)
      expect(9876, "")(nsca.getNscaPort)
    }

    "substitute strings in layered config file(s)" in {
      val nsca = new Nsca(this.getClass)
      expect("applicationService", "")(nsca.getNscaService)
      expect(Encryption.NONE, "")(nsca.getEncryptionMethod)
      expect("farFarAway", "")(nsca.getNscaHost)
      expect(9876, "")(nsca.getNscaPort)
    }

    "substitute strings in HOCON string" in {
      val string = "nsca { nscaHost = localhost \n nscaPort = 5667 \n nscaService = %packageName%.%className%.domainBus }"
      val nsca = new Nsca(string, this.getClass)
      expect("com.micronautics.nsca.NscaTest.domainBus", "")(nsca.getNscaService)
      expect(Encryption.NONE, "")(nsca.getEncryptionMethod)
      expect("localhost", "")(nsca.getNscaHost)
      expect(5667, "")(nsca.getNscaPort)
    }

    "point to arbitrary HOCON file" in {
      val contents = Nsca.getFileContents("application.conf")
      val nsca = new Nsca(contents, this.getClass)
      expect("applicationService", "")(nsca.getNscaService)
      expect(Encryption.NONE, "")(nsca.getEncryptionMethod)
      expect("farFarAway", "")(nsca.getNscaHost)
      expect(9876, "")(nsca.getNscaPort)
    }

    "respond to HOCON string" in {
      val nsca = new Nsca("nsca { nscaHost = localhost \n nscaPort = 5667 \n nscaService = domainBus }");
      expect("domainBus", "")(nsca.getNscaService)
      expect(Encryption.NONE, "")(nsca.getEncryptionMethod)
      expect("localhost", "")(nsca.getNscaHost)
      expect(5667, "")(nsca.getNscaPort)

      nsca.send(NagiosMsgLevel.UNKNOWN, "What's going on?")
      Thread.sleep(10000)
      nsca.send(NagiosMsgLevel.CRITICAL, "Test critical message")
      Thread.sleep(10000)
      nsca.send(NagiosMsgLevel.WARN, "Test warning message")
      Thread.sleep(10000)
      nsca.send(NagiosMsgLevel.OK, "Everything is peachy-keen")
    }
  }
}
