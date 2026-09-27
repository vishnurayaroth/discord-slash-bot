#!/bin/sh
# Render (and similar platforms) tell the container which port to listen on through $PORT and
# point their health check at exactly that value. A value entered by hand in the dashboard's
# Environment tab does not reliably change what Render's own health check targets, so this script
# reads whatever $PORT is actually present in the container at startup and configures Tomcat's
# connector to that port. Falls back to 8080 for a plain local `docker run` with no PORT set.
set -e
PORT="${PORT:-8080}"
sed -i "s/__HTTP_PORT__/${PORT}/" /usr/local/tomcat/conf/server.xml
exec "$@"
