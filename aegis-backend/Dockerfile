# OpenJDK 17 기반 이미지 사용
FROM openjdk:17-jdk-slim

# 작업 디렉토리 설정
WORKDIR /app

# build/libs 디렉토리에서 JAR 파일을 컨테이너로 복사
COPY build/libs/*.jar app.jar

# 컨테이너 포트 8080을 외부에 노출
EXPOSE 8080

# JAR 파일 실행
ENTRYPOINT ["java", "-jar", "app.jar"]
