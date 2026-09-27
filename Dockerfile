# build on the machine's own platform: java bytecode runs anywhere, so gradle never needs emulation
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src/main src/main
# the cache keeps gradle and the dependencies between builds
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q installDist

# only this stage is built for the target platform (linux/amd64 for cloud run)
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 ledgercore && useradd --system --uid 10001 --gid 10001 --no-create-home ledgercore
COPY --from=build /src/build/install/ledgercore /app
# numbers, not names, so kubernetes can check runAsNonRoot without reading /etc/passwd
USER 10001:10001
# the heap follows the container memory limit instead of the machine's
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
# exec form, and the start script ends with exec java, so java is pid 1 and gets SIGTERM
ENTRYPOINT ["/app/bin/ledgercore"]
