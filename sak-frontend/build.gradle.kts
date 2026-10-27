// sak-frontend, its build is packaged below frontend/ in a jar sak-backend serves it from at apps.<root_domain>
plugins {
    `java-library`
}

val npmCi by tasks.registering(Exec::class) {
    description = "Install the npm dependencies of the frontend"
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
}

val npmBuild by tasks.registering(Exec::class) {
    description = "Build the frontend"
    dependsOn(npmCi)
    commandLine("npm", "run", "build")
    inputs.dir("src")
    inputs.dir("public")
    inputs.files("angular.json", "tsconfig.json", "tsconfig.app.json", "package-lock.json")
    outputs.dir("dist")
}

val npmTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the unit tests of the frontend"
    dependsOn(npmCi)
    commandLine("npm", "test", "--", "--watch=false")
}

tasks.processResources {
    dependsOn(npmBuild)
    from(layout.projectDirectory.dir("dist/sak-frontend/browser")) {
        into("frontend")
    }
}

tasks.check {
    dependsOn(npmTest)
}
