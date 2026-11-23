plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.mongodb)

    testImplementation(testFixtures(project(":common")))
}
