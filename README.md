<a href="https://github.com/encalmo/setup-aws-credentials">![GitHub](https://img.shields.io/badge/github-%23121011.svg?style=for-the-badge&logo=github&logoColor=white)</a> <a href="https://central.sonatype.com/artifact/org.encalmo/setup-aws-credentials_3" target="_blank">![Maven Central Version](https://img.shields.io/maven-central/v/org.encalmo/setup-aws-credentials_3?style=for-the-badge)</a> <a href="https://encalmo.github.io/setup-aws-credentials/scaladoc/org/encalmo/aws.html" target="_blank"><img alt="Scaladoc" src="https://img.shields.io/badge/docs-scaladoc-red?style=for-the-badge"></a>

# setup-aws-credentials

Scala 3 utility to programatically compute AWS credentials using AWS CLI and AWS SSO. 

## Dependencies

   - [Scala](https://www.scala-lang.org/) >= 3.3.5
   - org.encalmo [**script-utils** 0.9.1](https://central.sonatype.com/artifact/org.encalmo/script-utils_3)

## Usage

Use with SBT

    libraryDependencies += "org.encalmo" %% "setup-aws-credentials" % "0.9.2"

or with SCALA-CLI

    //> using dep org.encalmo::setup-aws-credentials:0.9.2

## Example

Insert into your bash script to request and export local AWS credentials.

```
$(scala run --dependency=org.encalmo::setup-aws-credentials:0.9.2 --main-class=org.encalmo.aws.SetupAwsCredentials --quiet -- --profile encalmo)
```

## Test

```
scala run . --quiet -- --profile encalmo
```
