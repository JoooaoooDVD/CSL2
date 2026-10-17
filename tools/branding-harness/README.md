# CS Dynamic Branding harness

Desktop (JVM) check of the resource-pack generator against real Minecraft client jars.
No Android needed; it compiles the pure-Java part of `game/branding`.

```sh
SRC=app_cs/src/main/java/com/craftstudio/cslauncher/game/branding
mkdir -p /tmp/bh && javac -encoding UTF-8 -cp gson.jar -d /tmp/bh \
  $SRC/BrandingMode.java $SRC/BrandingPack.java $SRC/BrandingException.java $SRC/ClientJarFacts.java \
  $SRC/BrandingAssetSource.java $SRC/BrandingPackGenerator.java $SRC/GameOptionsFile.java tools/branding-harness/Harness.java
java -cp /tmp/bh:gson.jar Harness app_cs/src/main/assets/cs_branding /tmp/bh-work 1.21.1.jar 1.21.11.jar 26.3.jar
```

Checks: version facts (pack format form), generation + atomic install, reuse on the second run,
file presence, exact `pack.mcmeta`, options.txt activation/restore with other packs preserved,
and that a foreign `CS-Dynamic-Branding` folder (no marker) is never replaced.
