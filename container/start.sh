#!/bin/bash
set -e

python3 /opt/embed_server.py &
EMBED_PID=$!

docker-entrypoint.sh postgres &
PG_PID=$!

trap "kill $EMBED_PID $PG_PID 2>/dev/null; wait" SIGTERM SIGINT

wait -n $EMBED_PID $PG_PID
EXIT_CODE=$?
kill $EMBED_PID $PG_PID 2>/dev/null
wait
exit $EXIT_CODE
