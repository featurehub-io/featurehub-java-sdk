#!/bin/sh
set -x
cd support && mvn -DskipTests=true -f pom-tiles.xml install && mvn install && cd .. && mvn -T4C  -DskipTests=true clean install

