#!/bin/sh
# 0.14.2：把“电脑上跑 agentd”的 JVM 内存压到 500MB 以内。
# 默认 JVM 会按机器内存的 1/4 选最大堆（16GB 机器≈4GB），看起来就像 4G 占用；
# 这里显式固定堆/元空间/代码缓存，并关掉分层编译的 C2，降低启动与常驻。
set -eu
JAR="${1:-agentd/build/libs/agentd-all.jar}"
shift 2>/dev/null || true
exec java \
  -Xms32m -Xmx384m \
  -XX:MaxMetaspaceSize=96m \
  -XX:ReservedCodeCacheSize=64m \
  -XX:+UseSerialGC \
  -XX:TieredStopAtLevel=1 \
  -XX:MaxDirectMemorySize=64m \
  -jar "$JAR" "$@"
