---
id: overview_index
title: "Summary"
---

# Getting started

Low-level AWS wrapper for [ZIO](https://zio.dev) for _all_ AWS services using the AWS Java SDK v2.

The goal is to have access to all AWS functionality for cases when only a simple, direct access is
needed from a ZIO application, or to be used as a building block for higher level wrappers around specific services.

Check the [list of available artifacts](artifacts.html) to get started. 

The [wrapper page](wrapper.html) shows in details how the library wraps the underlying _Java SDK_. On the [configuration page](config.html) you
can learn more about how set the common properties of the AWS clients in addition to setting up one of the [HTTP implementations](http.html).

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

### Higher level AWS libraries
The following libraries are built on top of `zio-aws` providing higher level interfaces for specific AWS services:

- [ZIO Kinesis](https://github.com/svroonland/zio-kinesis)
- [ZIO SQS](https://github.com/zio/zio-sqs)

### Additional resources

- There is a [blog post](https://vigoo.github.io/posts/2020-09-23-zioaws-code-generation.html) explaining how the code generator is implemented.
- [This post](https://vigoo.github.io/posts/2020-11-01-zioaws-zioquery.html) shows an example of using `zio-aws` together with [ZIO Query](https://zio.github.io/zio-query/) 
