# When launching sbt, if the `AWS_JAVA_SDK_REPOSITORY` envvar is not setup in your local env,
# sbt will download the full `aws/aws-sdk-java-v2` GitHub repo, which is a big one and takes some time to clone
# This is annoying as each time we restart sbt, this repo is re-cloned in a random tmp directory
#
# This simple script helps you to configure your local env and clone the AWS repo so that sbt will not re-clone it
# each time it's restarted
#
cp .env.template .env || echo
git clone https://github.com/aws/aws-sdk-java-v2.git aws-repo