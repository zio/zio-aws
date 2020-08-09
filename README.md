# zio-aws

**WORK IN PROGRESS, NOT RELEASED YET**

Low-level AWS wrapper for [ZIO](https://zio.dev) for _all_ AWS services using the AWS Java SDK v2.

The goal is to have access to all AWS functionality for cases when only a simple, direct access is
needed from a ZIO application, or to be used as a building block for higher level wrappers around specific services. 

### Features
- Common configuration layer
- ZIO module layer per AWS service
- Wrapper for all operations on all services
- Http service implementations for functional Scala http libraries, injected through ZIO's module system
- ZStream wrapper around paginated operations
- **TODO** Service-specific extra configuration
- **TODO** More idiomatic Scala request and response types wrapping the Java classes
- **TODO** Generated error type

### Design
The library consists of a core module and one generated library for _each_ AWS service, based on the official JSON
schema contained in the AWS Java SDK's artifacts. By only providing a wrapper on top of the Java SDK the code
generator does not have to know all the implementation details and features of the schema.
 
That said in the future it is possible to replace the implementation to a fully generated native functional Scala solution
without breaking the generated APIs.
  
#### Modules
For each AWS Service the library defines a _ZIO module_ with wrapper functions for all the _operations_, and a `live` 
implementation that depends on a core _AWS configuration layer_:

```scala
val live: ZLayer[AwsConfig, Throwable, Ec2]
``` 

The `AwsConfig` layer defines how each service's async Java client gets configured, including the http client which is
provided by an other layer `AwsConfig` is depending on.

Each module has accessor functions for _all operations_ of the given service.

#### Operations

For simple request-response operations the library generates a very light wrapper (see below to learn about future
plans of wrapping the model types too):

```scala
def deleteVolume(request: DeleteVolumeRequest): ZIO[Ec2, AwsError, DeleteVolumeResponse]
```

For operations where either the input or the output or both are _byte streams_, a `ZStream` wrapper is generated:

```scala
def getObject(request: GetObjectRequest): ZIO[S3, AwsError, StreamingOutputResult[GetObjectResponse]]
def putObject(request: PutObjectRequest, body: ZStream[Any, AwsError, Chunk[Byte]])
```

where the output is a stream packed together with additional response data:

```scala
case class StreamingOutputResult[Response](response: Response,
                                           output: ZStream[Any, AwsError, Chunk[Byte]])
```

For operations with _event streams_ a `ZStream` of a model type gets generated:

```scala
def startStreamTranscription(request: StartStreamTranscriptionRequest, input: ZStream[Any, AwsError, AudioStream]): ZIO[TranscribeStreaming, AwsError, ZStream[Any, AwsError, TranscriptEvent]]
```

And for all operations that supports _pagination_, an alternative streaming wrappers gets generated with the postfix `Stream`:

```scala
def scanStream(request: ScanRequest): ZIO[DynamoDb, AwsError, ZStream[Any, AwsError, java.util.Map[String, AttributeValue]]]
```

#### Model wrappers
Currently work in progress, Scala _wrappers_ will be provided for each model type providing the following functionality:

- Case classes with default parameter values instead of the _builder pattern_
- Automatic conversion to Scala collection types
- ADTs instead of the Java enums 
 
### HTTP client
By default the AWS Java SDK uses _netty_ under the hood to make the HTTP client calls. `zio-aws` defines the http client
as a _layer_ (`HttpClient`) that has to be provided to the _AWS configuration layer_. 
 
Currently the following implementations can be used:
- `zio-aws-netty` contains the default netty implementation packed as a layer 
- `zio-aws-akka-http` is based on Matthias Lüneberg's [aws-spi-akka-http library](https://github.com/matsluni/aws-spi-akka-http)
- `zio-aws-http4s` is an implementation on top of _http4s_
 
### Build

Some custom sbt build tasks:
- `generateAll` to generate all the source code
- `buildAll` to generate and build all the libraries
- `publishLocalAll` to generate, build and publish all the libraries to the local Ivy repository
 
### Example
The following example uses the ElasticBeanstalk and EC2 APIs to print some info. 
 
**Please note that once the generated model wrappers will be ready, it will look even nicer**
 
```scala
object Main extends App {

  val program: ZIO[Console with Ec2 with ElasticBeanstalk, AwsError, Unit] =
    for {
      app <- elasticbeanstalk.describeApplications(
        DescribeApplicationsRequest.builder()
          .applicationNames("my-service")
          .build()
      ).map(_.applications().asScala.headOption)
      _ <- app match {
        case Some(appDescription) =>
          for {
            _ <- console.putStrLn(s"Got application description for ${appDescription.applicationName()}")
            envs <- elasticbeanstalk.describeEnvironments(
              DescribeEnvironmentsRequest.builder()
                .applicationName(appDescription.applicationName())
                .build()
            ).map(_.environments().asScala.toList)
            _ <- ZIO.foreach(envs) { env =>
              (for {
                _ <- console.putStrLn(s"Getting the EB resources of ${env.environmentName()}")
                instances <- elasticbeanstalk.describeEnvironmentResources(
                  DescribeEnvironmentResourcesRequest.builder()
                    .environmentId(env.environmentId())
                    .build()).map(_.environmentResources().instances().asScala)
                _ <- console.putStrLn(s"Getting the EC2 instances in ${env.environmentName()}")
                instanceIds = instances.map(_.id())
                _ <- console.putStrLn(s"Instance IDs are ${instanceIds.mkString(", ")}")
                reservationsStream <- ec2.describeInstancesStream(
                  DescribeInstancesRequest.builder()
                    .instanceIds(instanceIds.asJavaCollection)
                    .build())
                _ <- reservationsStream.run(Sink.foreach {
                  reservation =>
                    ZIO.foreach(reservation.instances().asScala.toList) { instance =>
                      for {
                        _ <- console.putStrLn(s"  instance ${instance.instanceId()}:")
                        _ <- console.putStrLn(s"    type: ${instance.instanceType()}")
                        _ <- console.putStrLn(s"    launched at: ${instance.launchTime()}")
                      } yield ()
                    }
                })
              } yield ()).catchAll { error =>
                console.putStrLnErr(s"Failed to get info for ${env.environmentName()}: $error")
              }
            }
          } yield ()
        case None =>
          ZIO.unit
      }
    } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val httpClient = http4s.client()
    //val httpClient = netty.client()
    val awsConfig = httpClient >>> core.config.default
    val aws = awsConfig >>> (ec2.live ++ elasticbeanstalk.live)

    program.provideCustomLayer(aws)
      .either
      .flatMap {
        case Left(error) =>
          console.putStrErr(s"AWS error: $error").as(ExitCode.failure)
        case Right(_) =>
          ZIO.unit.as(ExitCode.success)
      }
  }
}
``` 