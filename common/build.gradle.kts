plugins { `java-test-fixtures` }

dependencies {
    api(project(":proto"))
    api(libs.spring.boot.starter.grpc.server)
    api(libs.spring.boot.starter.actuator)
    api(libs.grpc.services)
    api(libs.grpc.netty)
    api(libs.caffeine)

    // Optional: only auth/sync put SQLite, and only content/gamification/insight put MongoDB, on their classpath.
    compileOnly(libs.spring.boot.starter.jdbc)
    compileOnly(libs.flyway.core)
    compileOnly(libs.sqlite.jdbc)
    compileOnly(libs.spring.boot.starter.mongodb)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.flyway.core)
    testImplementation(libs.sqlite.jdbc)

    testFixturesApi(libs.spring.boot.starter.test)
}
