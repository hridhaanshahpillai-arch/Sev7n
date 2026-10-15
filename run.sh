#!/bin/sh
# Builds Sev7n (if needed) and opens it, on macOS or Linux. Extra arguments are passed through,
# e.g.  ./run.sh projects/MyGame   or   ./run.sh --play projects/MyGame
cd "$(dirname "$0")"
[ -f build/sev7n.jar ] || sh build.sh || exit 1

if [ -n "$JAVA_HOME" ]; then JAVA="$JAVA_HOME/bin/java"; else JAVA=java; fi
# a Mac only lets the first thread open windows
if [ "$(uname)" = "Darwin" ]; then
    exec "$JAVA" -XstartOnFirstThread -jar build/sev7n.jar "$@"
else
    exec "$JAVA" -jar build/sev7n.jar "$@"
fi
