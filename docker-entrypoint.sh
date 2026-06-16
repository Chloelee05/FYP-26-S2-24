#!/bin/sh
set -e

PORT="${PORT:-8080}"
HTTP_MARKER='Connector port="8080" protocol="HTTP/1.1"'

if grep -q "$HTTP_MARKER" /usr/local/tomcat/conf/server.xml; then
  sed -i "s/port=\"8080\" protocol=\"HTTP\/1.1\"/port=\"${PORT}\" protocol=\"HTTP\/1.1\"/" \
    /usr/local/tomcat/conf/server.xml
fi

if [ -n "$AUCTION_UPLOAD_DIR" ]; then
  mkdir -p "$AUCTION_UPLOAD_DIR/auction" \
           "$AUCTION_UPLOAD_DIR/profile" \
           "$AUCTION_UPLOAD_DIR/support"
fi

exec catalina.sh run
