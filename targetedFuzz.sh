#!/bin/bash
# Find all unique instruction encodings used during the boot of a target,
# sorted rarest first, then feed them to the test generator.
set -e

JPC_VERSION=$(mvn -q -pl jpc-app -Dexpression=project.version -DforceStdout help:evaluate)
APP_JAR="target/jpc-app/JPCApplication.jar"
TOOLS_JAR="target/jpc-tools/jpc-tools-${JPC_VERSION}.jar"
CORE_JAR="target/jpc-core/jpc-core-${JPC_VERSION}.jar"

java -jar "$APP_JAR" -config "$1" -log-disam | sort | uniq -d -c | sort -n > "$1.disam"
java -cp "$TOOLS_JAR:$CORE_JAR" tools.Tools -testgen "$1.disam" -random
