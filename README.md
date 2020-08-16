# zio-aws

Low-level AWS wrapper for [ZIO](https://zio.dev) for _all_ AWS services using the AWS Java SDK v2.

The goal is to have access to all AWS functionality for cases when only a simple, direct access is
needed from a ZIO application, or to be used as a building block for higher level wrappers around specific services.

Check the [list of available artifacts](DEPENDENCIES.md) to get started. 

### Features
- Common configuration layer
- ZIO module layer per AWS service
- Wrapper for all operations on all services
- Http service implementations for functional Scala http libraries, injected through ZIO's module system
- ZStream wrapper around paginated operations
- Service-specific extra configuration
- More idiomatic Scala request and response types wrapping the Java classes

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
def putObject(request: PutObjectRequest, body: ZStream[Any, AwsError, Byte])
```

where the output is a stream packed together with additional response data:

```scala
case class StreamingOutputResult[Response](response: Response,
                                           output: ZStream[Any, AwsError, Byte])
```

For operations with _event streams_ a `ZStream` of a model type gets generated:

```scala
def startStreamTranscription(request: StartStreamTranscriptionRequest, input: ZStream[Any, AwsError, AudioStream]): ZStream[TranscribeStreaming, AwsError, TranscriptEvent]
```

And for all operations that supports _pagination_, an alternative streaming wrappers gets generated with the postfix `Stream`:

```scala
def scanStream(request: ScanRequest): ZStream[DynamoDb, AwsError, Map[String, AttributeValue]]
```

Note that for event streaming or paginating operations returning a `ZStream` the actual AWS call happens when the stream gets pulled.

#### Model wrappers
For each model type a set of wrappers are generated, providing the following functionality:

- Case classes with default parameter values instead of the _builder pattern_
- Automatic conversion to Scala collection types
- ADTs instead of the Java enums 
- ZIO functions to "get or fail" the optional model fields
- Primitive type aliases

The following example from the `elasticsearch` module shows how the generated case classes look like, to be used as input for the service operations:

```scala
case class DescribePackagesFilter(name: scala.Option[DescribePackagesFilterName] = None, 
                                  value: scala.Option[List[primitives.DescribePackagesFilterValue]] = None) {
    def buildAwsValue(): software.amazon.awssdk.services.elasticsearch.model.DescribePackagesFilter = {
      import DescribePackagesFilter.zioAwsBuilderHelper.BuilderOps
      software.amazon.awssdk.services.elasticsearch.model.DescribePackagesFilter
        .builder()
        .optionallyWith(name.map(value => value.unwrap))(_.name)
        .optionallyWith(value.map(value => value.map { item => item: java.lang.String }.asJava))(_.value)
        .build()
    }
}
```

When processing the _results_ of the operations (either directly or though the `ZStream` wrappers), the AWS Java model types are wrapped
by a _read-only wrapper interface_. The following example shows one from the `transcribe` module:

```scala
object CreateMedicalVocabularyResponse {
  private lazy val zioAwsBuilderHelper: io.github.vigoo.zioaws.core.BuilderHelper[software.amazon.awssdk.services.transcribe.model.CreateMedicalVocabularyResponse] = io.github.vigoo.zioaws.core.BuilderHelper.apply
  trait ReadOnly {
    def editable: CreateMedicalVocabularyResponse = CreateMedicalVocabularyResponse(vocabularyNameValue.map(value => value), languageCodeValue.map(value => value), vocabularyStateValue.map(value => value), lastModifiedTimeValue.map(value => value), failureReasonValue.map(value => value))
    def vocabularyNameValue: scala.Option[primitives.VocabularyName]
    def languageCodeValue: scala.Option[LanguageCode]
    def vocabularyStateValue: scala.Option[VocabularyState]
    def lastModifiedTimeValue: scala.Option[primitives.DateTime]
    def failureReasonValue: scala.Option[primitives.FailureReason]
    def vocabularyName: ZIO[Any, io.github.vigoo.zioaws.core.AwsError, primitives.VocabularyName] = io.github.vigoo.zioaws.core.AwsError.unwrapOptionField("vocabularyName", vocabularyNameValue)
    def languageCode: ZIO[Any, io.github.vigoo.zioaws.core.AwsError, LanguageCode] = io.github.vigoo.zioaws.core.AwsError.unwrapOptionField("languageCode", languageCodeValue)
    def vocabularyState: ZIO[Any, io.github.vigoo.zioaws.core.AwsError, VocabularyState] = io.github.vigoo.zioaws.core.AwsError.unwrapOptionField("vocabularyState", vocabularyStateValue)
    def lastModifiedTime: ZIO[Any, io.github.vigoo.zioaws.core.AwsError, primitives.DateTime] = io.github.vigoo.zioaws.core.AwsError.unwrapOptionField("lastModifiedTime", lastModifiedTimeValue)
    def failureReason: ZIO[Any, io.github.vigoo.zioaws.core.AwsError, primitives.FailureReason] = io.github.vigoo.zioaws.core.AwsError.unwrapOptionField("failureReason", failureReasonValue)
  }
  private class Wrapper(impl: software.amazon.awssdk.services.transcribe.model.CreateMedicalVocabularyResponse) extends CreateMedicalVocabularyResponse.ReadOnly {
    override def vocabularyNameValue: scala.Option[primitives.VocabularyName] = scala.Option(impl.vocabularyName()).map(value => value: primitives.VocabularyName)
    override def languageCodeValue: scala.Option[LanguageCode] = scala.Option(impl.languageCode()).map(value => LanguageCode.wrap(value))
    override def vocabularyStateValue: scala.Option[VocabularyState] = scala.Option(impl.vocabularyState()).map(value => VocabularyState.wrap(value))
    override def lastModifiedTimeValue: scala.Option[primitives.DateTime] = scala.Option(impl.lastModifiedTime()).map(value => value: primitives.DateTime)
    override def failureReasonValue: scala.Option[primitives.FailureReason] = scala.Option(impl.failureReason()).map(value => value: primitives.FailureReason)
  }
  def wrap(impl: software.amazon.awssdk.services.transcribe.model.CreateMedicalVocabularyResponse): ReadOnly = new Wrapper(impl)
}
```

As a large part of the models in the AWS SDK are defined as _optional_, the generated wrapper also contains ZIO accessor functions,
which lift the option value to make it more comfortable to chain the AWS operations.
 
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
      appsResult <- elasticbeanstalk.describeApplications(DescribeApplicationsRequest(applicationNames = Some(List("my-service"))))
      app <- appsResult.applications.map(_.headOption)
      _ <- app match {
        case Some(appDescription) =>
          for {
            applicationName <- appDescription.applicationName
            _ <- console.putStrLn(s"Got application description for $applicationName")

            envsResult <- elasticbeanstalk.describeEnvironments(DescribeEnvironmentsRequest(applicationName = Some(applicationName)))
            envs <- envsResult.environments

            _ <- ZIO.foreach(envs) { env =>
              env.environmentName.flatMap { environmentName =>
                (for {
                  environmentId <- env.environmentId
                  _ <- console.putStrLn(s"Getting the EB resources of $environmentName")

                  resourcesResult <- elasticbeanstalk.describeEnvironmentResources(DescribeEnvironmentResourcesRequest(environmentId = Some(environmentId)))
                  resources <- resourcesResult.environmentResources
                  _ <- console.putStrLn(s"Getting the EC2 instances in $environmentName")
                  instances <- resources.instances
                  instanceIds <- ZIO.foreach(instances)(_.id)
                  _ <- console.putStrLn(s"Instance IDs are ${instanceIds.mkString(", ")}")

                  reservationsStream <- ec2.describeInstancesStream(DescribeInstancesRequest(instanceIds = Some(instanceIds)))
                  _ <- reservationsStream.run(Sink.foreach {
                    reservation =>
                      reservation.instances.flatMap { instances =>
                        ZIO.foreach(instances) { instance =>
                          for {
                            id <- instance.instanceId
                            typ <- instance.instanceType
                            launchTime <- instance.launchTime
                            _ <- console.putStrLn(s"  instance $id:")
                            _ <- console.putStrLn(s"    type: $typ")
                            _ <- console.putStrLn(s"    launched at: $launchTime")
                          } yield ()
                        }
                      }
                  })
                } yield ()).catchAll { error =>
                  console.putStrLnErr(s"Failed to get info for $environmentName: $error")
                }
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

### Version history
#### 2.13.69.0
API breaking changes to make the streaming interface more ergonomic:
- Input/output byte streams are now flat (`ZStream[Any, AwsError, Byte]` instead of `ZStream[Any, AwsError, Chunk[Byte]`)
- Streaming operations return a `ZStream` that performs the request on first pull instead of a `ZIO[..., ZStream[...]]`

Scala 2.12 version of the artifacts.

#### 1.13.69.1
Initial release republished with fixed metadata in POMs

#### 1.13.69.0
Initial release based on AWS Java SDK 2.13.69  