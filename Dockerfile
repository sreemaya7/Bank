FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY . .

RUN javac -cp "sqlite-jdbc-3.45.1.0.jar:slf4j-api-2.0.9.jar:slf4j-simple-2.0.9.jar" BankApiServer.java

CMD ["java", "-cp", ".:sqlite-jdbc-3.45.1.0.jar:slf4j-api-2.0.9.jar:slf4j-simple-2.0.9.jar", "BankApiServer"]