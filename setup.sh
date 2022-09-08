#!/bin/sh
cd support
mvn -f pom-tiles.xml install
mvn install
cd ..

