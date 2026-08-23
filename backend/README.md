# backend

Java 21 + Spring Boot modular monolith.

Bootstrapped in `FZ-002`. See `../docs/02-architecture.md` for the target module structure and stack, and `../CLAUDE.md` §8 for commands.

## Quick start

```bash
./mvnw clean verify       # build + test
./mvnw spring-boot:run    # run locally (port 8080)
curl http://localhost:8080/actuator/health
```

Requires Java 21 — `.java-version` pins this via [jenv](https://github.com/jenv/jenv); otherwise set `JAVA_HOME` to a Java 21 JDK.
