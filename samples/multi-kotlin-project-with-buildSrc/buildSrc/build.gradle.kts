buildscript {
    dependencies {
        classpath(kotlinModule("gradle-plugin"))
    }
    repositories {
        gradleScriptKotlin()
    }
}

apply { plugin("kotlin") }

dependencies {
    compileOnly(gradleScriptKotlinApi())
    compile(kotlinModule("stdlib"))
}

repositories {
    gradleScriptKotlin()
}