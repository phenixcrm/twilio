plugins {
  war
}

dependencies {
  testImplementation(
    "org.junit.jupiter:junit-jupiter-api:5.9.0"
  )
  testRuntimeOnly(
    "org.junit.jupiter:junit-jupiter-engine:5.9.0"
  )
  implementation(project(":common"))
  implementation(project(":core"))
  implementation(project(":potion:api"))
  implementation(project(":validation"))
  implementation(project(":types"))
  implementation(project(":util"))
  implementation(project(":cli"))
  implementation(project(":sql"))
  implementation(libs.postgresql)
  implementation(libs.servlet)
  implementation(libs.websocket)
  implementation(libs.twilio)
  implementation(libs.redis)
  implementation(libs.dotenv)
  implementation(libs.csv)

}
