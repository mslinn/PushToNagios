The core code from [NagiosAppender](https://sourceforge.net/news/?group_id=140996) was extracted into this project
and heavily refactored so it no longer depends on log4j. The result is a simple native Java facility for pushing
notifications to a properly configured Nagios server. The original project used MDC, which is not compatible with Akka.
MDC uses ThreadLocal variables, which are verboten.

See the documentation on [how to set up Nagios and NSCA server](https://docs.google.com/document/d/1DGrlGG87oZdEvDJ1b6Z8JRxOggCDMTp2kUAgj4oJ1Jg/edit#).

This project uses the concept of 'channels' to Nagios.
A channel is supported by an instance of the `Nsca` class,
and delivers messages to a specific Nagios service on a unique domain and port.
Each channel has distinct state, but they all share the same threadpool.
Each message is sent on a separate thread.

Channels are immutable.
You can create channels by specifying various properties files to the `Nsca` constructor,
or you can specify each property individually. See the
[unit tests](https://github.com/mslinn/PushToNagios/blob/master/src/test/scala/com/micronautics/nsca/NscaTest.scala)
for examples of how to create channels.

The properties files are hierarchically parsed by the Typesafe Config utility.
Two special substitutions are made, if an appropriate constructor is called.
The constructors that accept a class as the last parameter translate strings in the configuration files that contain
`%packageName%` to the package name of the class, and `%className%` to the unqualified class name. The same is true
for the constructor that accepts a configuration string and a class.

Modifications to the original NagiosAppender project were sponsored by [Bookish, LLC](http://bookish.com)

## Installation

 1. Add this to your project's `build.sbt` (remember that file requires double-spacing):

        libraryDependencies += "com.micronautics" % "PushToNagios" % "0.2.0-SNAPSHOT" withSources()

        // The sbt-plugin-releases resolver should not be required for SBT 0.12, but it is required for SBT 0.11.3:

        //resolvers += Resolver.url("sbt-plugin-releases", new URL("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases/"))(Resolver.ivyStylePatterns)

        // The following resolver will continue to be required for SNAPSHOTS:

        resolvers += Resolver.url("sbt-plugin-snapshots", new URL("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-snapshots/"))(Resolver.ivyStylePatterns)

