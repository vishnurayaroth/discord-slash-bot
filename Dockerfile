# Stage 1: build the WAR with Maven on Java 17
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# Downloads from Maven Central occasionally fail with a transient TLS error ("Tag mismatch").
# Single-threaded downloads are more reliable, and a failed attempt is simply retried.
RUN for i in 1 2 3 4 5; do \
      mvn -B -q -Dmaven.artifact.threads=1 package -DskipTests && exit 0; \
      echo "build attempt $i failed, retrying"; sleep 5; \
    done; exit 1

# Stage 2: run it as ROOT.war on Tomcat 10.1 (served at "/")
FROM tomcat:10.1-jdk17-temurin
# Render's free instance has 512 MB RAM and 0.1 CPU: fewer request threads, capped heap.
# These are starting values to tune in the first deploy (research.md R16).
RUN rm -rf /usr/local/tomcat/webapps/* \
 && sed -i 's|<Connector port="8080" protocol="HTTP/1.1"|<Connector port="8080" protocol="HTTP/1.1" maxThreads="20"|' /usr/local/tomcat/conf/server.xml \
 && grep -q 'maxThreads="20"' /usr/local/tomcat/conf/server.xml
COPY --from=build /build/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
ENV CATALINA_OPTS="-Xmx256m -XX:MaxMetaspaceSize=128m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
# Set PORT=8080 on Render so it finds Tomcat
EXPOSE 8080
