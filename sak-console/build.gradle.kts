// the angular console, its build is packaged below console/ in a jar sak-controller serves it from
plugins {
    `java-library`
}

val npmCi by tasks.registering(Exec::class) {
    description = "Install the npm dependencies of the console"
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
}

val npmBuild by tasks.registering(Exec::class) {
    description = "Build the console"
    dependsOn(npmCi)
    commandLine("npm", "run", "build")
    inputs.dir("src")
    inputs.dir("public")
    inputs.files("angular.json", "tsconfig.json", "tsconfig.app.json", "package-lock.json")
    outputs.dir("dist")
}

val npmTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the unit tests of the console"
    dependsOn(npmCi)
    commandLine("npm", "test", "--", "--watch=false")
}

// drives the console in a browser against the controller, mailpit (docker) receives the
// login links; not part of check because it needs docker and a playwright browser
val playwrightInstall by tasks.registering(Exec::class) {
    description = "Install the browser the end-to-end tests run in"
    dependsOn(npmCi)
    commandLine("npx", "playwright", "install", "chromium")
}

val e2eTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the end-to-end tests of the console against the controller and a mailpit e-mail sink"
    dependsOn(playwrightInstall, ":sak-controller:installDist", ":sak-controller:vectorTest")
    commandLine("npx", "playwright", "test")
}

tasks.processResources {
    dependsOn(npmBuild)
    from(layout.projectDirectory.dir("dist/sak-console/browser")) {
        into("console")
    }
}

tasks.check {
    dependsOn(npmTest)
}
