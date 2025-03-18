![Maven Central Version](https://img.shields.io/maven-central/v/org.encalmo/setup-aws-credentials_3?style=for-the-badge)

# setup-aws-credentials

Scala 3 utility to programatically compute AWS credentials using AWS CLI and AWS SSO. 

## Dependencies

## Usage

Use with SBT

    libraryDependencies += "org.encalmo" %% "setup-aws-credentials" % "0.9.0"

or with SCALA-CLI

    //> using dep org.encalmo::setup-aws-credentials:0.9.0

## Example

Insert into your bash script to request and export local AWS credentials.

```
$(scala run --dependency=org.encalmo::setup-aws-credentials:0.9.0 --main-class=org.encalmo.aws.SetupAwsCredentials --quiet -- --profile encalmo)
```

## Test

```
scala run . --quiet -- --profile encalmo
```
