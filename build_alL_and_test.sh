#!/bin/sh
set -x
export MVN_OPTS="--batch-mode --quiet"
MAVEN_OPTS=${MVN_OPTS:-"-T4C"}
cd support && mvn -f pom-tiles.xml install && mvn install && cd .. && mvn $MAVEN_OPTS clean install

