# zio-aws

*WORK IN PROGRESS, NOT READY TO BE USED YET*

Low-level AWS wrapper for [ZIO](https://zio.dev) for _all_ AWS services using the AWS Java SDK v2.

Features:
- Common configuration layer
- ZIO module layer per AWS service
- Wrapper for all operations on all services
- Http service implementations for functional Scala http libraries, injected through ZIO's module system
- **TODO** ZStream wrapper around paginated operations
- **TODO** More idiomatic Scala request and response types wrapping the Java classes
- **TODO** Generated error type

 