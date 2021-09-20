import groovy.util.logging.Slf4j
import org.gradle.testkit.runner.GradleRunner
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.spock.Testcontainers
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise

import java.time.Duration

@Slf4j
@Stepwise
@Testcontainers
class DeployTest extends Specification {

   @Shared
   DockerComposeContainer environment =
           new DockerComposeContainer<>(new File('docker-compose.yml'))
                   .withExposedService("ksqldb-server", 8088, Wait.forHealthcheck().withStartupTimeout(Duration.ofMinutes(5)))
                   .withExposedService('kafka', 29092)
                   .withLocalCompose(true)

   @Shared
   File projectDir, buildDir, resourcesDir, settingsFile, artifact, buildFile

   @Shared
   def result

   @Shared
   String projectName = 'simple-deploy', taskName, kafka, endpoint

   @Shared
   String analyticsVersion = System.getProperty("analyticsVersion")

   def setupSpec() {

      projectDir = new File("${System.getProperty("projectDir")}/$projectName")
      buildDir = new File(projectDir, 'build')
      artifact = new File(buildDir, 'distributions/simple-deploy-pipeline.zip')
      resourcesDir = new File('src/test/resources')
      settingsFile = new File(projectDir, 'settings.gradle').write("""rootProject.name = '$projectName'""")
      buildFile = new File(projectDir, 'build.gradle')
      endpoint = "http://${environment.getServiceHost('ksqldb-server', 8088)}:${environment.getServicePort('ksqldb-server', 8088)}".toString()
      kafka = "${environment.getServiceHost('kafka', 29092)}:${environment.getServicePort('kafka', 29092)}".toString()

      buildFile.write("""
               |plugins {
               |  id 'com.redpillanalytics.gradle-confluent'
               |  id "com.redpillanalytics.gradle-analytics" version "$analyticsVersion"
               |  id 'maven-publish'
               |}
               |
               |publishing {
               |  repositories {
               |    mavenLocal()
               |  }
               |}
               |group = 'com.redpillanalytics'
               |version = '1.0.0'
               |
               |repositories {
               |  jcenter()
               |  mavenLocal()
               |  maven {
               |     name 'test'
               |     url 'maven'
               |  }
               |}
               |
               |dependencies {
               |   archives group: 'com.redpillanalytics', name: 'simple-build', version: '+'
               |   archives group: 'com.redpillanalytics', name: 'simple-build-pipeline', version: '+'
               |}
               |
               |confluent {
               |  functionPattern = 'simple-build'
               |  pipelineEndpoint = '$endpoint'
               |}
               |analytics {
               |   kafka {
               |     test {
               |        bootstrapServers = '$kafka'
               |     }
               |  }
               |}
               |""".stripMargin())
   }

   def setup() {
      new AntBuilder().copy(todir: projectDir) {
         fileset(dir: resourcesDir)
      }
   }

   // helper method
   def executeSingleTask(String taskName, List otherArgs = []) {
      otherArgs.add(0, taskName)
      log.warn "runner arguments: ${otherArgs.toString()}"

      // execute the Gradle test build
      result = GradleRunner.create()
              .withProjectDir(projectDir)
              .withArguments(otherArgs)
              .withPluginClasspath()
              .forwardOutput()
              .build()
   }

   def "Execute :tasks task"() {
      given:
      taskName = 'tasks'
      result = executeSingleTask(taskName, ['-Si'])

      expect:
      !result.tasks.collect { it.outcome }.contains('FAILED')
   }

   def "Deploy test from Resources"() {
      given:
      taskName = 'deploy'
      result = executeSingleTask(taskName, ['-Si'])

      expect:
      !result.tasks.collect { it.outcome }.contains('FAILED')
      result.tasks.collect { it.path - ":" } == ["functionCopy", "pipelineExtract", "pipelineDeploy", "deploy"]
   }

   def "Producer test to Kafka"() {
      given:
      taskName = 'producer'
      result = executeSingleTask(taskName, ['-Si'])
      log.warn "Kafka: $kafka"

      expect:
      !result.tasks.collect { it.outcome }.contains('FAILED')
      result.tasks.collect { it.path - ":" } == ['kafkaTestSink', 'producer']
   }
}
