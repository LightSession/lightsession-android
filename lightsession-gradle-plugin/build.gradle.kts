plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

// AGP's Instrumentation API and the ASM it ships with. `compileOnly` because both are on the
// classpath of the build that applies this plugin — bundling a second copy of either is how a
// plugin ends up fighting the AGP version the consumer chose.
dependencies {
    compileOnly("com.android.tools.build:gradle:8.7.3")
    compileOnly("org.ow2.asm:asm:9.7")
    compileOnly("org.ow2.asm:asm-commons:9.7")
}

gradlePlugin {
    plugins {
        create("lightsession") {
            id = "com.lightsession.instrumentation"
            implementationClass = "com.lightsession.gradle.LightSessionPlugin"
        }
    }
}
