#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "--> Packaging server and client jars..."
mvn -pl server,client -am package -DskipTests

echo "--> Building registration-server:latest..."
docker build -t registration-server:latest -f server/Dockerfile server/

echo "--> Building registration-client:latest..."
docker build -t registration-client:latest -f client/Dockerfile client/

echo "--> Done."
