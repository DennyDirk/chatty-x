FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre AS demo
RUN groupadd -g 10001 chatty && useradd -u 10001 -g chatty -m chatty && mkdir /data && chown chatty:chatty /data
WORKDIR /app
COPY --from=build /build/target/chatty-x-0.1.0.jar app.jar
USER 10001:10001
ENV DATA_DIR=/data
ENTRYPOINT ["java","-jar","/app/app.jar"]

FROM chatty-x-tdlib:ea97bcdd AS production
RUN groupadd -g 10001 chatty && useradd -u 10001 -g chatty -m chatty && mkdir /data && chown chatty:chatty /data
WORKDIR /app
COPY --from=build /build/target/chatty-x-0.1.0.jar app.jar
USER 10001:10001
ENV DATA_DIR=/data
ENTRYPOINT ["java","--enable-native-access=ALL-UNNAMED","-Djava.library.path=/opt/tdlib/bin","-Dloader.path=/opt/tdlib/bin","-cp","/app/app.jar","org.springframework.boot.loader.launch.PropertiesLauncher"]
