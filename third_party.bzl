load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_jvm_external//:specs.bzl", "maven","parse")
load("//:import_external_alias.bzl", "import_external_alias")


def dependency(coordinates,exclusions=None):
    artifact = parse.parse_maven_coordinate(coordinates)
    return maven.artifact(
            group =  artifact['group'],
            artifact = artifact['artifact'],
            packaging =  artifact.get('packaging'),
            classifier = artifact.get('classifier'),
            version =  artifact['version'],
            exclusions = exclusions,
        )

scala_version = "2.12.12"

deps = [
    dependency("ch.qos.logback:logback-classic:1.1.11"),
    dependency("com.fasterxml.jackson.core:jackson-annotations:2.9.0"),
    dependency("com.fasterxml.jackson.core:jackson-core:2.9.6"),
    dependency("com.fasterxml.jackson.core:jackson-databind:2.9.6"),
    dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-csv:2.10.0"),
    dependency("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.9.6"),
    dependency("com.fasterxml.jackson.module:jackson-module-paranamer:2.9.6"),
    dependency("com.fasterxml.jackson.module:jackson-module-scala_2.12:2.9.6"),
    dependency("com.github.luben:zstd-jni:1.4.3-1"),
    dependency("com.google.guava:guava:16.0.1"),
    dependency("com.h2database:h2:1.4.197"),
    dependency("com.thoughtworks.paranamer:paranamer:2.8"),
    dependency("com.typesafe.scala-logging:scala-logging_2.12:3.8.0"),
    dependency("com.yammer.metrics:metrics-core:2.2.0"),
    dependency("commons-cli:commons-cli:1.4"),
    dependency("dev.zio:zio_2.12:1.0.7"),
    dependency("dev.zio:zio-stacktracer_2.12:1.0.7"),
    dependency("dev.zio:zio-streams_2.12:1.0.7"),
    dependency("dev.zio:zio-test_2.12:1.0.7"),
    dependency("dev.zio:zio-test-junit_2.12:1.0.7"),
    dependency("junit:junit:4.13"),
    dependency("net.sf.jopt-simple:jopt-simple:5.0.4"),
    dependency("org.apache.curator:curator-test:2.12.0"),
    dependency("org.apache.kafka:kafka_2.12:2.7.0"),
    dependency("org.apache.kafka:kafka-clients:2.7.0"),
    dependency("org.apache.zookeeper:zookeeper:3.4.10"),
    dependency("org.hamcrest:hamcrest-core:1.3"),
    dependency("org.javassist:javassist:3.18.1-GA"),
    dependency("org.lz4:lz4-java:1.6.0"),
    dependency("org.portable-scala:portable-scala-reflect_2.12:0.1.0"),
    dependency("org.scala-lang.modules:scala-collection-compat_2.12:2.1.2"),
    dependency("org.scala-lang:scala-compiler:" + scala_version),
    dependency("org.scala-lang.modules:scala-parser-combinators_2.12:1.0.6"),
    dependency("org.scala-lang.modules:scala-xml_2.12:1.1.0"),
    dependency("org.scala-lang:scala-library:" + scala_version),
    dependency("org.scala-lang:scala-reflect:" + scala_version),
    dependency("org.scala-sbt:test-interface:1.0"),
    dependency("org.slf4j:slf4j-api:1.7.25"),
    dependency("org.specs2:specs2-common_2.12:4.8.3"),
    dependency("org.specs2:specs2-core_2.12:4.8.3"),
    dependency("org.specs2:specs2-fp_2.12:4.8.3"),
    dependency("org.specs2:specs2-junit_2.12:4.8.3"),
    dependency("org.specs2:specs2-matcher_2.12:4.8.3"),
    dependency("org.specs2:specs2-mock_2.12:4.8.3"),
    dependency("org.xerial.snappy:snappy-java:1.1.7.1"),
    dependency("com.kubukoz:better-tostring_" + scala_version +":0.2.4"),
]

def dependencies():
    maven_install(
        artifacts = deps,
        repositories = [
            "https://repo.maven.apache.org/maven2/",
            "https://mvnrepository.com/artifact",
            "https://maven-central.storage.googleapis.com",
            ],
        generate_compat_repositories = True,
#        maven_install_json = "//:maven_install.json",
    )
    # this is a compiler plugin with full scala version in its maven artifact name
    # so we create an alias without the minor scala version
    import_external_alias(
       name = "com_kubukoz_better_tostring_2_12",
       actual = "@maven//:com_kubukoz_better_tostring_" + scala_version.replace(".", "_"),
    )

