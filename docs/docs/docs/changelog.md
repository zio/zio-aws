---
layout: docs
title: Version history
---

# Version history

Note: this is a manually maintained list of important changes. Because of having auto-release from CI, this
list may not reflect _all_ changes immediately. 

#### 3.15.19.10
- Fix for libraries that use the `Integer` alias for ints (such as `zio-aws-sqs`)

#### 3.15.19.8
- Updated [fs2](https://fs2.io) release that fixes the http4s backend

#### 3.15.19.7
- Fix for event streaming wrappers such as `subscribeToShard` in `zio-aws-kinesis`

#### 3.15.16.0

- Introduced automatic release from CI, each new AWS SDK release triggers a new `zio-aws` build now
- zio-config support 

#### 2.14.7.0

- Updated to AWS SDK 2.14.7
- Fix an [issue](https://github.com/vigoo/zio-aws/issues/23) with http4s streaming uploads
- `Iterable` in place of `List` in the request models
- The akka-http client now gets the _actor system_ from the environment
- Code generator rewritten as an sbt plugin

#### 2.14.3.0
API breaking changes to make the streaming interface more ergonomic:
- Input/output byte streams are now flat (`ZStream[Any, AwsError, Byte]` instead of `ZStream[Any, AwsError, Chunk[Byte]`)
- Streaming operations return a `ZStream` that performs the request on first pull instead of a `ZIO[..., ZStream[...]]`
- Streaming for paginated operations that does not have a paginator in the Java SDK
- No `xxxStream` variants, streaming is the default and only interface for paginable operaitons
- Updated to AWS SDK 2.14.3
- Fixed handling of some error cases
- Scala 2.12 version is now available

#### 1.13.69.1
Initial release republished with fixed metadata in POMs

#### 1.13.69.0
Initial release based on AWS Java SDK 2.13.69  
