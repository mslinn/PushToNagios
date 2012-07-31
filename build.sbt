organization := "com.micronautics"

name := "PushToNagios"

version := "0.1.0-SNAPSHOT"

scalaVersion := "2.9.1-1"

scalaVersion in update := "2.9.1-1"

scalacOptions ++= Seq("-deprecation", "-unchecked")

libraryDependencies ++= Seq(
  "ch.qos.logback" %  "logback-classic" % "1.0.0" withSources(),
  "org.scalatest"  %  "scalatest_2.9.2" % "1.7.1" % "test" withSources()
)

publishTo <<= (version) { version: String =>
   val scalasbt = "http://scalasbt.artifactoryonline.com/scalasbt/"
   val (name, url) = if (version.contains("-SNAPSHOT"))
                       ("sbt-plugin-snapshots", scalasbt+"sbt-plugin-snapshots")
                     else
                       ("sbt-plugin-releases", scalasbt+"sbt-plugin-releases")
   Some(Resolver.url(name, new URL(url))(Resolver.ivyStylePatterns))
}

publishMavenStyle := false
