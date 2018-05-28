scalaVersion := "2.12.2"

scalaSource in Compile <<= baseDirectory(_ / "src")

scalacOptions ++= Seq("-deprecation", "-unchecked", "-Xfatal-warnings",
                      "-encoding", "us-ascii")

libraryDependencies +=
  "org.nlogo" % "NetLogo" % "6.0.3" from
    "http://ccl.northwestern.edu/netlogo/6.0.3/netlogo-6.0.3.jar"

artifactName := { (_, _, _) => "auction.jar" }

packageOptions := Seq(
  Package.ManifestAttributes(
    ("Extension-Name", "auction"),
    ("Class-Manager", "AuctionExtension"),
    ("NetLogo-Extension-API-Version", "6.0.3")))

packageBin in Compile <<= (packageBin in Compile, baseDirectory, streams) map {
  (jar, base, s) =>
    IO.copyFile(jar, base / "auction.jar")
    Process("pack200 --modification-time=latest --effort=9 --strip-debug " +
            "--no-keep-file-order --unknown-attribute=strip " +
            "auction.jar.pack.gz auction.jar").!!
    if(Process("git diff --quiet --exit-code HEAD").! == 0) {
      Process("git archive -o auction.zip --prefix=auction/ HEAD").!!
      IO.createDirectory(base / "auction")
      IO.copyFile(base / "auction.jar", base / "auction" / "auction.jar")
      IO.copyFile(base / "auction.jar.pack.gz", base / "auction" / "auction.jar.pack.gz")
      Process("zip auction.zip auction/auction.jar auction/auction.jar.pack.gz").!!
      IO.delete(base / "auction")
    }
    else {
      s.log.warn("working tree not clean; no zip archive made")
      IO.delete(base / "auction.zip")
    }
    jar
  }

cleanFiles <++= baseDirectory { base =>
  Seq(base / "auction.jar",
      base / "auction.jar.pack.gz",
      base / "auction.zip") }
