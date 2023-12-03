FROM openjdk:17-alpine
COPY build/install/nsd /app
CMD /app/bin/nsd
