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
# The shutdown port (8005) has no "address" attribute in the stock image, so it binds every
# interface, not just localhost. Render's deploy process port-scans the container and, finding
# 8005 open, sends it an HTTP probe; Tomcat correctly refuses it ("Invalid shutdown command"),
# and the repeated failure times out the deploy before Render tries the real port (8080).
# Disabling the listener (port -1) removes it entirely: Docker/Render stop the container with a
# normal process signal, which the JVM's shutdown hook already handles, so no shutdown port is
# needed in a container.
# The Connector's port is a placeholder, __HTTP_PORT__, filled in at container start by
# docker-entrypoint.sh from the real $PORT value. Render's health check targets whatever port it
# assigns the container (seen live at 10000, not the 8080 we hardcoded before), and a dashboard
# environment variable named PORT does not reliably change that, so the container must read the
# real value from its own environment at startup rather than assume a fixed number.
RUN rm -rf /usr/local/tomcat/webapps/* \
 && sed -i 's|<Server port="8005" shutdown="SHUTDOWN">|<Server port="-1" shutdown="SHUTDOWN">|' /usr/local/tomcat/conf/server.xml \
 && grep -q 'Server port="-1"' /usr/local/tomcat/conf/server.xml \
 && sed -i 's|<Connector port="8080" protocol="HTTP/1.1"|<Connector port="__HTTP_PORT__" protocol="HTTP/1.1" maxThreads="20"|' /usr/local/tomcat/conf/server.xml \
 && grep -q 'maxThreads="20"' /usr/local/tomcat/conf/server.xml \
 && grep -q '__HTTP_PORT__' /usr/local/tomcat/conf/server.xml
COPY --from=build /build/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
ENV CATALINA_OPTS="-Xmx256m -XX:MaxMetaspaceSize=128m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
# 8080 is only the default for a local `docker run` with no PORT set; the real port at runtime
# comes from $PORT (see docker-entrypoint.sh).
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["catalina.sh", "run"]
