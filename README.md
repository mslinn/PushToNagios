The core code from [NagiosAppender](https://sourceforge.net/news/?group_id=140996) was extracted into this project
and heavily refactored so it no longer depends on log4j. The result is a simple native Java facility for pushing
notifications to a properly configured Nagios server. The original project used MDC, which is not compatible with Akka.
MDC uses ThreadLocal variables, which are verboten.

See the documentation on [how to set up Nagios and NSCA server](https://docs.google.com/document/d/1DGrlGG87oZdEvDJ1b6Z8JRxOggCDMTp2kUAgj4oJ1Jg/edit#).
