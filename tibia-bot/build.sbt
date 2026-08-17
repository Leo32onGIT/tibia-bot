import com.typesafe.sbt.packager.docker.Cmd

name := "violent-bot-dedicated"
version := "2.2"

scalaVersion := "2.13.18"

enablePlugins(DockerPlugin)
enablePlugins(JavaAppPackaging)
// Pins the packaged image's entrypoint/launcher script to the bot itself.
// sbt-native-packager generates one launcher script per discovered `main`
// method; naming it explicitly guarantees the single `violent-bot-dedicated`
// script docker-compose.yml's image expects to run by default, and keeps that
// true if another entry point is ever added.
Compile / mainClass := Some("com.tibiabot.BotApp")
dockerExposedPorts += 443
// Status dashboard: internal-only, reached via Caddy on the docker-compose
// network — never published to the host directly (see docker-compose.yml).
dockerExposedPorts += 8080
dockerBaseImage := "eclipse-temurin:8-jre"
// The respawn board is drawn with Java2D (presentation.RespawnBoardImage). A
// JVM with no DISPLAY infers this on its own, so this is insurance rather than a
// fix: it states the intent, and keeps the board rendering if a future base
// image ever arrives with a display attached.
Universal / javaOptions += "-Djava.awt.headless=true"
// Also tag the built image `:latest` so docker-compose.yml can reference a
// stable tag (otherwise only the version tag is created).
dockerUpdateLatest := true

// The sprite cache's directory has to exist in the image, owned by the user the
// container actually runs as.
//
// sbt-native-packager runs the app as uid 1001 and leaves /opt/docker owned by
// root with no group write, so the app cannot create a directory there at all.
// Worse, a named volume mounted at a path the image does not have gets its
// mountpoint created by Docker as root — which is what happened here: the
// volume existed, the directory existed, and every write into it was denied.
// Docker copies the image directory's ownership into an empty named volume, so
// creating it here with the right owner fixes both the volume case and the
// no-volume case.
//
// Keyed on the USER line native-packager emits. If that ever changes shape this
// silently stops applying, which is why CreatureSpriteCache also checks the
// directory is writable at startup and says so plainly rather than failing one
// sprite at a time.
dockerCommands := dockerCommands.value.flatMap {
  case user @ Cmd("USER", args @ _*) if args.mkString(" ") == "1001:0" =>
    Seq(
      Cmd("RUN", "mkdir -p /opt/docker/cache/sprites && chown -R 1001:0 /opt/docker/cache " +
        "&& chmod -R 775 /opt/docker/cache"),
      user
    )
  case other => Seq(other)
}

val AkkaHttpVersion = "10.5.0"

libraryDependencies += "com.typesafe" % "config" % "1.4.2"
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.7.0"
libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % "2.7.0"
libraryDependencies += "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion
libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.3.16"
libraryDependencies += "org.codehaus.janino" % "janino" % "3.1.6"
libraryDependencies += "com.github.napstr" % "logback-discord-appender" % "1.0.0"
libraryDependencies += "net.dv8tion" % "JDA" % "6.5.0"
libraryDependencies += "club.minnced" % "discord-webhooks" % "0.8.2"
libraryDependencies += "org.apache.commons" % "commons-text" % "1.10.0"
libraryDependencies += "org.postgresql" % "postgresql" % "42.5.4"
libraryDependencies += "com.google.guava" % "guava" % "30.1.1-jre"
libraryDependencies += "io.lettuce" % "lettuce-core" % "6.2.6.RELEASE"

libraryDependencies += "org.scalactic" %% "scalactic" % "3.2.15"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.15" % Test
libraryDependencies += "org.scalamock" %% "scalamock" % "5.2.0" % Test
// Lets web.DiscordAuthSpec drive the OAuth routes as real requests — the login
// and callback-failure branches are all cookie and redirect behaviour, which is
// only meaningful end to end.
libraryDependencies += "com.typesafe.akka" %% "akka-http-testkit" % AkkaHttpVersion % Test
libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.7.0" % Test
libraryDependencies += "com.typesafe.akka" %% "akka-stream-testkit" % "2.7.0" % Test
libraryDependencies += "com.softwaremill.sttp.client3" %% "core" % "3.3.18"
libraryDependencies += "org.jsoup" % "jsoup" % "1.17.2"
libraryDependencies += "io.circe" %% "circe-core" % "0.14.10"
libraryDependencies += "io.circe" %% "circe-parser" % "0.14.10"

resolvers += "jitpack" at "https://jitpack.io"
