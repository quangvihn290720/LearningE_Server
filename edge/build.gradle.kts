plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter.webflux)

    testImplementation(testFixtures(project(":common")))
}
