# exact versions with digests, so the same commit always builds the same image
# build on the machine's own platform: java bytecode runs anywhere, so gradle never needs emulation
FROM --platform=$BUILDPLATFORM eclipse-temurin:21.0.12.1_1-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src/main src/main
# the cache keeps gradle and the dependencies between builds
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q installDist

# only this stage is built for the target platform (linux/amd64 for cloud run)
FROM eclipse-temurin:21.0.12.1_1-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
RUN groupadd --system --gid 10001 ledgercore && useradd --system --uid 10001 --gid 10001 --no-create-home ledgercore
COPY --from=build /src/build/install/ledgercore /app
# numbers, not names, so kubernetes can check runAsNonRoot without reading /etc/passwd
USER 10001:10001
# the heap follows the container memory limit instead of the machine's
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
# exec form, and the start script ends with exec java, so java is pid 1 and gets SIGTERM
ENTRYPOINT ["/app/bin/ledgercore"]
