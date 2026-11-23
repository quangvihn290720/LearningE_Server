plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":common"))
    listOf("edge", "auth", "sync", "content", "gamification", "insight").forEach {
        implementation(project(":$it"))
    }
    testImplementation(testFixtures(project(":common")))
    testImplementation(libs.spring.boot.starter.jdbc)
}

tasks.test { systemProperty("content.dir", rootProject.file("content-data").path) }

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
