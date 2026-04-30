#!/bin/sh
# Regenerate src/.../decoder/ExecutableTables.java from the Opcodes_*.xml tables.
set -e

mvn -pl jpc-tools -am -DskipTests package

JPC_VERSION=$(mvn -q -pl jpc-tools -Dexpression=project.version -DforceStdout help:evaluate)

java -cp "target/jpc-tools/jpc-tools-${JPC_VERSION}.jar:target/jpc-core/jpc-core-${JPC_VERSION}.jar" \
    tools.Tools -decoder \
    > jpc-core/src/main/java/org/jpc/emulator/execution/decoder/ExecutableTables.java

mvn -pl jpc-app -am -DskipTests package
