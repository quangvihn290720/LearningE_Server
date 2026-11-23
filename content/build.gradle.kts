plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.mongodb)
    implementation(libs.jackson.databind)

    testImplementation(testFixtures(project(":common")))
}

tasks.test {
    // The real content files are validated and imported by the tests.
    inputs.dir(rootProject.file("content-data"))
    systemProperty("content.dir", rootProject.file("content-data").path)
}
