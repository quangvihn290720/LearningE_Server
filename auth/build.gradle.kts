plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.sqlite.jdbc)

    testImplementation(testFixtures(project(":common")))
}
