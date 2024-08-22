#!/bin/sh
set -x
MAVEN_OPTS=${MVN_OPTS}
echo "cd support && mvn -f pom-tiles.xml install && mvn install && cd .. && mvn $MAVEN_OPTS clean install"
cd support && mvn -f pom-tiles.xml install && mvn install && cd .. && mvn $MAVEN_OPTS clean install

