organization := "com.micronautics"

name := "PushToNagios"

version := "0.1.0-SNAPSHOT"

scalaVersion := "2.9.1-1"

scalaVersion in update := "2.9.1-1"

scalacOptions ++= Seq("-deprecation")

libraryDependencies ++= Seq(
  "ch.qos.logback" %  "logback-classic" % "1.0.0" withSources(),
  "org.scalatest"  %  "scalatest_2.9.2" % "1.7.1" % "test" withSources()
)

com.micronautics.dependencyReport.DependencyReport.dependencyReportSettings
