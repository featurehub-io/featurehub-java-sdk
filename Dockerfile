FROM eclipse-temurin:25-jdk-alpine

ARG client
ARG exampleFolder

WORKDIR /app
COPY . /app/
RUN cd support && mvn -DskipTests -f pom-tiles.xml install && mvn -DskipTests install
RUN cd core && mvn -DskipTests install && cd ../client-implementations/$client && mvn -DskipTests install
RUN cd $exampleFolder && mvn -DskipTests package
