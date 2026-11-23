plugins {
    alias(libs.plugins.protobuf)
}

dependencies {
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.protobuf.java)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protoc.get()}" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpcJava.get()}" }
    }
    generateProtoTasks {
        all().configureEach {
            plugins { create("grpc") { option("@generated=omit") } }
        }
    }
}
