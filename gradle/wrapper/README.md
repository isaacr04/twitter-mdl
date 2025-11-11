# Gradle Wrapper JAR

The `gradle-wrapper.jar` file is required for the Gradle wrapper to function.

To generate it, run the following command from the project root:

```bash
gradle wrapper --gradle-version 8.2
```

Alternatively, if you open this project in Android Studio, it will automatically download the Gradle wrapper files.

The gradle-wrapper.jar file is intentionally excluded from this repository but will be automatically downloaded when you run:

```bash
./gradlew build
```

Or when Android Studio syncs the project.
