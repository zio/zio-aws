---
layout: docs
title: Wrappers
---

# Client wrappers

### Modules
For each AWS Service the library defines a _ZIO module_ with wrapper functions for all the _operations_, and a `live` 
implementation that depends on a core _AWS configuration layer_:

```scala
val live: ZLayer[AwsConfig, Throwable, Ec2]
``` 

The `AwsConfig` layer defines how each service's async Java client gets configured, including the http client which is
provided by an other layer `AwsConfig` is depending on.

Each module has accessor functions for _all operations_ of the given service.

### Operations

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

And for all operations that supports _pagination_, streaming wrappers gets generated:

```scala
def scan(request: ScanRequest): ZStream[DynamoDb, AwsError, Map[String, AttributeValue]]
```

Note that for event streaming or paginating operations returning a `ZStream` the actual AWS call happens when the stream gets pulled.

### Model wrappers
For each model type a set of wrappers are generated, providing the following functionality:

- Case classes with default parameter values instead of the _builder pattern_
- Automatic conversion to Scala collection types
- ADTs instead of the Java enums 
- ZIO functions to "get or fail" the optional model fields
- Primitive type aliases

The following example from the `elasticsearch` module shows how the generated case classes look like, to be used as input for the service operations:

```scala
case class DescribePackagesFilter(name: scala.Option[DescribePackagesFilterName] = None, 
                                  value: scala.Option[Iterable[primitives.DescribePackagesFilterValue]] = None) {
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