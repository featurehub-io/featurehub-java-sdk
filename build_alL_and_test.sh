#!/bin/sh
cd support && mvn -f pom-tiles.xml install && mvn install && cd .. && mvn -T4C clean install

