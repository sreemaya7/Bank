FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY . .

RUN javac -cp sqlite-jdbc-3.45.1.0.jar BankApiServer.java

CMD ["java", "-cp", ".:sqlite-jdbc-3.45.1.0.jar", "BankApiServer"]