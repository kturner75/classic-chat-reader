# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/com/classicchatreader`: Spring Boot backend (controllers, services, repositories, entities, models).
- `src/main/resources`: App config (`application.properties`) and static frontend assets under `static/` (HTML/CSS/JS).
- `src/test/java/com/classicchatreader`: JUnit/Spring Boot tests for controllers, services, and parsers.
- `data/`: Local book or ingest data (keep large assets out of git if possible).
- `target/`: Maven build output (generated; do not edit).

## Build, Test, and Development Commands
- `mvn clean compile`: Compile the project.
- `mvn test`: Run all tests.
- `mvn clean package`: Build the runnable JAR in `target/`.
- `mvn spring-boot:run`: Run the app locally on port 8080.
- `mvn test -Dtest=TestClassName`: Run a single test class.
- `mvn test -Dtest=TestClassName#testMethod`: Run a single test method.

## Coding Style & Naming Conventions
- Java follows standard conventions: 4-space indentation, braces on the same line, and `UpperCamelCase` class names.
- Packages are lowercase (e.g., `com.classicchatreader.service`).
- Keep controllers thin, push business logic into `service` classes, and use `repository` for persistence access.
- Frontend files live in `src/main/resources/static` and should remain framework-free (vanilla HTML/CSS/JS).

## Testing Guidelines
- Tests use Spring Boot’s test starter (JUnit 5).
- Name tests after the unit under test (e.g., `SearchServiceTest`).
- Prefer small, focused tests; include controller tests for API behavior and parser tests for ingest logic.

## Commit & Pull Request Guidelines
- No strict commit convention is enforced; recent history uses short, sentence-style messages. Prefer imperative, concise summaries (e.g., “Add search indexing for chapters”).
- PRs should include a brief description, linked issue (if applicable), and screenshots for UI changes in `static/`.

## Configuration & Data Notes
- App settings live in `src/main/resources/application.properties`.
- If adding external services (e.g., TTS or image generation), document required env vars in the PR description.
