plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(project(":common"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.mongodb)
    implementation(libs.jackson.databind)

    testImplementation(testFixtures(project(":common")))
}

springBoot { mainClass = "com.learninge.server.content.ContentApplication" }

// Authoring tool, run through tools/content.ps1. Needs neither the server nor MongoDB.
tasks.register<JavaExec>("contentTool") {
    description = "Kiểm, khóa, xuất/nhập nội dung học."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.learninge.server.content.tool.ContentTool"
    workingDir = rootDir
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    systemProperty("content.dir", rootProject.file("content-data").path)
}

tasks.test {
    // The real content files are validated and imported by the tests.
    inputs.dir(rootProject.file("content-data"))
    systemProperty("content.dir", rootProject.file("content-data").path)
}
