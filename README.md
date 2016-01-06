## Demonstration of a possible Akka HTTP server layer bug
The `bug.serverlayer.ServerLayerTest` is a test which uses the `Http().serverLayer` as an HTTP parser. It sends an HTTP request through the `Http().serverLayer` and then an HTTP reply in the other direction.

The test in the `master` branch shows that it works correctly in Akka HTTP version 1.0.

The test in the `version-2.0.1` branch shows that it breaks in Akka HTTP version 2.0.1 (the test fails).

### Gradle Commands Overview
 * clean - deletes previous build
 * eclipse - creates Eclipse workspace meta-files (for IntelliJ use the IDE Gradle importer)
 * build - builds project and creates jar files of subprojects
 * test - execute tests and produce TestNG output in build/test-results
