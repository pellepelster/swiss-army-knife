// sak-frontend, its build is packaged below frontend/ in a jar sak-backend serves it from at sak.<base_domain>
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
    // the libraries: core, testing and one for each app
    inputs.dir("projects")
    inputs.dir("public")
    // the favicons and the icons of the apps (angular.json)
    inputs.dir("../sak-theme/images/web")
    inputs.files("angular.json", "tsconfig.json", "tsconfig.app.json", "package-lock.json")
    outputs.dir("dist")
}

// every file of the frontend that is written by hand is formatted with prettier (.prettierrc); the
// generated ones are left out (.prettierignore). `npm run format` formats them
val npmFormatCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "Check the formatting of the frontend"
    dependsOn(npmCi)
    commandLine("npm", "run", "format:check")
}

val npmTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the unit tests of the frontend"
    dependsOn(npmCi, npmFormatCheck)
    commandLine("npm", "test", "--", "--watch=false")
}

// drives the apps in a browser against the controller, mailpit (docker) receives the login links,
// vector and victorialogs (docker) the access log; not part of check because it needs docker and
// a playwright browser
val playwrightInstall by tasks.registering(Exec::class) {
    description = "Install the browser the end-to-end tests run in"
    dependsOn(npmCi)
    commandLine("npx", "playwright", "install", "chromium")
}

val e2eTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the end-to-end tests of the apps against the controller and a mailpit e-mail sink"
    dependsOn(playwrightInstall, ":sak-backend:installDist", ":sak-backend:vectorTest")
    commandLine("npx", "playwright", "test")
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
