enablePlugins(org.nlogo.build.NetLogoExtension)

netLogoExtName      := "auction"

netLogoClassManager := "org.nlogo.auction.AuctionExtension"

netLogoZipSources   := false

scalaVersion           := "2.12.0"

scalaSource in Compile := baseDirectory.value / "src"

scalacOptions          ++= Seq("-deprecation", "-unchecked", "-Xfatal-warnings", "-encoding", "us-ascii")

// The remainder of this file is for options specific to bundled netlogo extensions
// netLogoTarget := org.nlogo.build.NetLogoExtension.directoryTarget(baseDirectory.value)

netLogoVersion := "6.0.3"
