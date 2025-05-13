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
  implementation(project(":phenix:common"))
  implementation(project(":libs:core"))
  implementation(project(":libs:potion:api"))
  implementation(project(":libs:validation"))
  implementation(project(":libs:types"))
  implementation(project(":libs:util"))
  implementation(project(":libs:cli"))
  implementation(project(":libs:sql"))
  implementation(libs.postgresql)
  implementation(libs.servlet)
  implementation(libs.websocket)
  implementation(libs.twilio)
  implementation(libs.redis)
  implementation(libs.dotenv)
  implementation(libs.csv)

}
