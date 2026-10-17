# Virtual LAN core harness (desktop)

Runs the pure-Java core of the Virtual LAN Multiplayer feature on a Linux/macOS machine:
a **host** session and a **guest** session in one JVM, talking to the real EasyTier engine and
the real public relay nodes, with a fake "Open to LAN" Minecraft world (status ping + multicast
announcement). It exercises LAN detection, the session directory, the tunnel, port forwarding,
world open/close propagation, host end → guest `HOST_ENDED`, and kick → `KICKED`.

```bash
# 1. engine binaries for the desktop architecture (x86_64 shown)
scripts/fetch_easytier.sh /tmp/jnitest

# 2. org.json + androidx annotation stubs
mkdir -p /tmp/lib /tmp/stubs/androidx/annotation
curl -sL -o /tmp/lib/json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
for a in NonNull Nullable MainThread WorkerThread; do
  printf 'package androidx.annotation;\npublic @interface %s {}\n' $a > /tmp/stubs/androidx/annotation/$a.java
done

# 3. compile core + harness
mkdir -p /tmp/jc /tmp/harness
javac -encoding UTF-8 -source 8 -target 8 -cp /tmp/lib/json.jar -d /tmp/jc \
  /tmp/stubs/androidx/annotation/*.java \
  app_cs/src/main/java/com/craftstudio/cslauncher/multiplayer/core/*.java
javac -cp /tmp/jc:/tmp/lib/json.jar -d /tmp/harness tools/vlan-harness/Harness.java

# 4. run (add "kick" to test removing the guest, "relay" to forbid direct paths so the
#    guest MUST carry its traffic through the public relay nodes)
java -Dengine.dir=/tmp/jnitest/x86_64 -cp /tmp/harness:/tmp/jc:/tmp/lib/json.jar Harness
java -Dengine.dir=/tmp/jnitest/x86_64 -cp /tmp/harness:/tmp/jc:/tmp/lib/json.jar Harness kick
java -Dengine.dir=/tmp/jnitest/x86_64 -cp /tmp/harness:/tmp/jc:/tmp/lib/json.jar Harness relay
# "vpn" plugs in a fake VpnController (session must wait for the TUN); "revoke" revokes it mid-session
java -Dengine.dir=/tmp/jnitest/x86_64 -cp /tmp/harness:/tmp/jc:/tmp/lib/json.jar Harness vpn
java -Dengine.dir=/tmp/jnitest/x86_64 -cp /tmp/harness:/tmp/jc:/tmp/lib/json.jar Harness revoke
```

Expected: guest reaches `CONNECTED_*`, `PING THROUGH TUNNEL -> Status{...}` succeeds, world
close/reopen is mirrored on the guest, host end → guest `ENDED HOST_ENDED`, and
`remaining easytier processes: 0` at the end.
