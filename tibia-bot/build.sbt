import com.typesafe.sbt.packager.docker.Cmd

name := "violent-bot-dedicated"
version := "2.2"

scalaVersion := "2.13.18"

// Turn scalac's "1 deprecation, re-run with -deprecation" summary lines into
// the actual offending code. A warning reduced to a count is one nobody acts
// on; these two flags cost nothing and make any new warning readable where it
// happened. The build is warning-free as of this commit.
//
// Deliberately not -Xlint: it finds another hundred things in code that already
// works, which is a separate piece of work from keeping the build quiet.
// -release pins the JDK API this compiles against to the one the image ships
// (`dockerBaseImage` below), which matters because a developer may build on any
// JDK they happen to have. Without it, calling a method newer than the runtime
// compiles clean locally and fails on the deploy box, which is the worst
// possible place to find out. With it, the local build fails immediately and
// says which method. Keep this and `dockerBaseImage` on the same number.
scalacOptions ++= Seq("-deprecation", "-feature", "-release:25")

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
// Pekko 1.7 reaches for `sun.misc.Unsafe`, which JDK 24 marked terminally
// deprecated, so every start prints four WARNING lines before any of our own
// logging. They come from the JVM on stderr rather than through logback, so they
// reach `docker logs` and stop there — the dashboard never sees them.
//
// Do not go looking at the class the warning names. It reports whichever call
// lands first, which is `org.apache.pekko.util.Unsafe` reading a field offset off
// `String.value` to run a hash fast-path — the harmless one. What would actually
// kill the bot is `pekko.dispatch.AbstractNodeQueue`, whose lock-free mailbox
// queues are built on compareAndSwap: run with
// `--sun-misc-unsafe-memory-access=deny` and it fails in that class's static
// initialiser while ActorSystem is being constructed, i.e. at BotApp.scala's
// first line of work, with nothing started.
//
// Not urgent, and not silenced. Both JDK 25 and JDK 26 default to `warn` (checked
// against the shipped 26 binary, which contradicts the widely repeated claim that
// 26 throws), so nothing flips under us while this image pins its own JDK — and
// `--sun-misc-unsafe-memory-access=allow` is a one-line reprieve if a later JDK
// does deny by default before the real fix is available. It stays visible because
// a suppressed warning is one nobody acts on.
//
// The real fix is verified, not hoped for: Pekko moved these 21 classes to
// VarHandle, and `pekko 2.0.0-M4` + `pekko-http 2.0.0-M1` builds this project
// with ZERO source changes, passes all 1828 tests warning-free, and starts
// cleanly under `=deny`. It is not taken yet only because pekko-http's 2.x line
// has sat at its first milestone since Jan 2026 while core reached M4 — and
// pekko-http is this bot's hot path. When 2.0.0 goes final this is a two-line
// version bump; re-run the suite and drop this comment.
dockerBaseImage := "eclipse-temurin:25-jre"
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

val PekkoVersion = "1.7.0"
val PekkoHttpVersion = "1.4.0"

libraryDependencies += "com.typesafe" % "config" % "1.4.9"
libraryDependencies += "org.apache.pekko" %% "pekko-stream" % PekkoVersion
libraryDependencies += "org.apache.pekko" %% "pekko-slf4j" % PekkoVersion
libraryDependencies += "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion
libraryDependencies += "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.6.3"
libraryDependencies += "org.codehaus.janino" % "janino" % "3.1.12"
libraryDependencies += "com.github.napstr" % "logback-discord-appender" % "1.0.0"
libraryDependencies += "net.dv8tion" % "JDA" % "6.5.0"
libraryDependencies += "club.minnced" % "discord-webhooks" % "0.8.4"
libraryDependencies += "org.apache.commons" % "commons-text" % "1.15.0"
libraryDependencies += "org.postgresql" % "postgresql" % "42.7.13"
// Connection pooling in front of that driver — see PooledConnectionProvider.
libraryDependencies += "com.zaxxer" % "HikariCP" % "7.1.0"
libraryDependencies += "io.lettuce" % "lettuce-core" % "7.7.0.RELEASE"

libraryDependencies += "org.scalactic" %% "scalactic" % "3.2.20"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.20" % Test
// Lets web.DiscordAuthSpec drive the OAuth routes as real requests — the login
// and callback-failure branches are all cookie and redirect behaviour, which is
// only meaningful end to end.
libraryDependencies += "org.apache.pekko" %% "pekko-http-testkit" % PekkoHttpVersion % Test
libraryDependencies += "org.apache.pekko" %% "pekko-testkit" % PekkoVersion % Test
libraryDependencies += "org.apache.pekko" %% "pekko-stream-testkit" % PekkoVersion % Test
libraryDependencies += "com.softwaremill.sttp.client3" %% "core" % "3.11.0"
libraryDependencies += "org.jsoup" % "jsoup" % "1.23.2"
libraryDependencies += "io.circe" %% "circe-core" % "0.14.16"
libraryDependencies += "io.circe" %% "circe-parser" % "0.14.16"

resolvers += "jitpack" at "https://jitpack.io"
