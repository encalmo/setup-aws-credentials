# setup-aws-credentials

Scala 3 utility to programatically compute AWS credentials using AWS CLI and AWS SSO. 

## Usage

Use with SBT

    libraryDependencies += "org.encalmo" %% "setup-aws-credentials" % "0.9.0"

or with SCALA-CLI

    //> using dep org.encalmo::setup-aws-credentials:0.9.0

## Example

Insert into your bash script to request and export local AWS credentials.

```
$(scala run --dependency=org.encalmo::setup-aws-credentials:0.9.0 --quiet -- --profile encalmo)
```

## Test

```
scala run . --quiet -- --profile encalmo
```