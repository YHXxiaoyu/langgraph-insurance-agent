# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY docker/maven-settings.xml ./docker/maven-settings.xml
COPY pom.xml .
COPY src ./src

# /root/.m2 用 cache mount：重新构建时依赖不用重下，镜像层里也不会多出几百 MB
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -s docker/maven-settings.xml clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 只把 fat jar 拷进来，源码、Maven、依赖缓存都不进镜像
COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

# 配置全部来自环境变量（compose 的 env_file + environment）
ENTRYPOINT ["java", "-jar", "app.jar"]