package org.encalmo.aws

import org.encalmo.utils.CommandLineUtils.*
import os.Path
import upickle.default.ReadWriter
import upickle.default.read

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import scala.io.AnsiColor.*
import scala.util.Try

object SetupAwsCredentials {

  def main(args: Array[String]): Unit = {
    val profile: String = requiredScriptParameter('p', "profile")(args)
    val accountIdArg: Option[String] = optionalScriptParameter('a', "account")(args)
    val roleNameArg: Option[String] = optionalScriptParameter('r', "role")(args)
    val force: Boolean = optionalScriptFlag('f', "force")(args)
    val verbose: Boolean = optionalScriptFlag('v', "verbose")(args)

    val credentials = apply(profile, accountIdArg, roleNameArg, force, verbose)

    credentials.fold(
      error =>
        System.err.println(error)
        System.exit(2)
      ,
      credentials =>
        println(
          s"""|export AWS_ACCESS_KEY_ID=${credentials.accessKeyId}
            |export AWS_SECRET_ACCESS_KEY=${credentials.secretAccessKey}
            |export AWS_SESSION_TOKEN=${credentials.sessionToken}
            |export AWS_PROFILE=${credentials.profile}
            |export AWS_DEFAULT_REGION=${credentials.defaultRegion}
            """.stripMargin
        )
    )
  }

  lazy val awsConfigFile: Path =
    os.Path(System.getProperty("user.home")) / ".aws" / "config"

  lazy val awsSsoCacheFolder: Path =
    os.Path(System.getProperty("user.home")) / ".aws" / "sso" / "cache"

  def apply(
      profile: String,
      accountIdArg: Option[String] = None,
      roleNameArg: Option[String] = None,
      force: Boolean = false,
      verbose: Boolean = false
  ): Either[String, Credentials] = {

    val credentialsCacheFile = awsSsoCacheFolder / s"credentials-$profile.json"

    val existingCredentials: Option[Credentials] =
      if !force && (os.exists(credentialsCacheFile) && os.isFile(credentialsCacheFile))
      then Some(read[Credentials](os.read(credentialsCacheFile)))
      else None

    if (!os.exists(awsConfigFile))
    then
      Left(
        s"${RED}File ${YELLOW}$awsConfigFile${RESET}${RED} does not exist ${YELLOW}${BOLD}$profile${RESET}"
      )
    else {

      val awsConfigOpt: Option[AwsConfig] = readAwsConfig(awsConfigFile, verbose)

      val defaultRegion: String = awsConfigOpt
        .flatMap(_.getProfileProperty(profile, "region"))
        .getOrElse("us-east-1")

      if (!awsConfigOpt.exists(_.getProfile(profile).isDefined))
      then
        Left(
          s"${RED}File ${YELLOW}$awsConfigFile${RESET}${RED} does not know about profile ${YELLOW}${BOLD}$profile${RESET}"
        )
      else {

        val loginProfile = awsConfigOpt
          .flatMap(_.getSourceProfileOf(profile, "sso_start_url"))
          .getOrElse(profile)

        for {
          accountId <- accountIdArg
            .orElse {
              awsConfigOpt.flatMap(_.getProfileProperty(profile, "sso_account_id"))
            }
            .toRight {
              s"${RED}${BOLD}Missing accountId parameter${RESET}.\n${RED}Tried to find ${YELLOW}sso_account_id${RESET}${RED} property in the ~/.aws/config profile ${YELLOW}$profile${RESET}${RED} but found none.${RESET}"
            }
          roleName <- roleNameArg
            .orElse {
              awsConfigOpt.flatMap(_.getProfileProperty(profile, "sso_role_name"))
            }
            .toRight {
              s"${RED}${BOLD}Missing accountId parameter${RESET}.\n${RED}Tried to find ${YELLOW}sso_role_name${RESET}${RED} property in the ~/.aws/config profile ${YELLOW}$profile${RESET}${RED} but found none.${RESET}"
            }
          effectiveCredentials <- existingCredentials match {
            case Some(credentials) if (credentials.expiration > (System.currentTimeMillis() - 5000L)) =>
              if (credentials.accountId == accountId && credentials.roleName == roleName)
              then Right(credentials)
              else computeNewCredentials(accountId, roleName, profile, loginProfile, defaultRegion, verbose)

            case _ =>
              computeNewCredentials(accountId, roleName, profile, loginProfile, defaultRegion, verbose)
          }
        } yield effectiveCredentials
      }
    }

  }

  def computeNewCredentials(
      accountId: String,
      roleName: String,
      profile: String,
      loginProfile: String,
      defaultRegion: String,
      verbose: Boolean
  ): Either[String, Credentials] = {
    val credentials =
      getAwsCredentials(accountId, roleName, profile, loginProfile, verbose)
        .map(c =>
          Credentials(
            accessKeyId = c.roleCredentials.accessKeyId,
            secretAccessKey = c.roleCredentials.secretAccessKey,
            sessionToken = c.roleCredentials.sessionToken,
            expiration = c.roleCredentials.expiration,
            accountId = accountId,
            roleName = roleName,
            profile = profile,
            defaultRegion = defaultRegion
          )
        )
    // save new credentials in a file
    credentials.map(c =>
      os.write.over(
        awsSsoCacheFolder / s"credentials-$profile.json",
        upickle.default.write(c),
        perms = os.PermSet.fromString("rw-------")
      )
    )
    credentials
  }

  def getAwsCredentials(
      accountId: String,
      roleName: String,
      profile: String,
      loginProfile: String,
      verbose: Boolean
  ): Either[String, AwsCredentials] = {
    if (verbose) then println(s"Log in using profile $loginProfile")
    os
      .list(awsSsoCacheFolder)
      .map(path =>
        try (Some(upickle.default.read[AwsSsoConfig](os.read(path))))
        catch { case e => None }
      )
      .collect { case Some(x) => x }
      .sortBy(_.timestamp)
      .foldLeft[Either[String, AwsCredentials]](Left("None AWS SSO credentials file found")) { case (result, config) =>
        result.orElse {
          if config.timestamp.isAfter(ZonedDateTime.now())
          then {
            val result = os
              .proc(
                "aws",
                "sso",
                "get-role-credentials",
                "--role-name",
                roleName,
                "--account-id",
                accountId,
                "--access-token",
                config.accessToken,
                "--profile",
                loginProfile
              )
              .call(os.pwd, check = false, stdout = os.Pipe, stderr = os.Pipe, mergeErrIntoOut = true)

            if (result.exitCode == 0)
            then
              if (verbose) then System.out.println(s"${WHITE}${result.out.text()}${RESET}")
              Try(read[AwsCredentials](result.out.text())).toEither.left.map(_.toString())
            else Left(result.err.text())
          } else result
        }
      }
      .orElse {
        loginUsingAwsSso(loginProfile, verbose)
          .flatMap(_ => getAwsCredentials(accountId, roleName, profile, loginProfile, verbose))
      }
  }

  def loginUsingAwsSso(profile: String, verbose: Boolean): Either[String, Unit] = {
    val result = os
      .proc(
        "aws",
        "sso",
        "login",
        "--profile",
        profile
      )
      .call(
        os.pwd,
        check = false
      )
    if (result.exitCode == 0)
    then {
      if (verbose) then System.out.println(s"${WHITE}${result.out.text()}${RESET}")
      Right(())
    } else
      System.err.println(s"${RED}${result.err.text()}${RESET}")
      Left(result.err.text())
  }

  def readAwsConfig(awsConfigFile: os.Path, verbose: Boolean): Option[AwsConfig] = {

    val profileHeaderRegex = "\\[\\s*profile (.+)\\s*\\]".r
    val propertyRegex = "(.+)=(.+)".r

    if (os.exists(awsConfigFile) && os.isFile(awsConfigFile))
    then {
      val awsConfig =
        os.read(awsConfigFile)
          .linesIterator
          .foldLeft(new AwsConfig(verbose)) { case (config, line) =>
            line.trim() match {
              case profileHeaderRegex(profileName) => config.startProfile(profileName.trim())
              case propertyRegex(key, value)       => config.addProperty(key.trim(), value.trim())
              case _                               => config
            }
          }
      if (verbose) println(s"${GREEN}AWS config file read successfully.${RESET}")
      Some(awsConfig)
    } else None
  }

  case class AwsSsoConfig(accessToken: String, expiresAt: String) derives ReadWriter {
    val timestamp =
      ZonedDateTime.parse(expiresAt, DateTimeFormatter.ISO_DATE_TIME)
  }

  case class Credentials(
      accessKeyId: String,
      secretAccessKey: String,
      sessionToken: String,
      expiration: Long,
      accountId: String,
      roleName: String,
      profile: String,
      defaultRegion: String
  ) derives ReadWriter {

    def toEnvironmentVariables: Map[String, String] =
      Map(
        "AWS_ACCESS_KEY_ID" -> accessKeyId,
        "AWS_SECRET_ACCESS_KEY" -> secretAccessKey,
        "AWS_SESSION_TOKEN" -> sessionToken,
        "AWS_PROFILE" -> profile,
        "AWS_DEFAULT_REGION" -> defaultRegion
      )
  }

  case class RoleCredentials(
      accessKeyId: String,
      secretAccessKey: String,
      sessionToken: String,
      expiration: Long
  ) derives ReadWriter

  case class AwsCredentials(roleCredentials: RoleCredentials) derives ReadWriter

  class AwsConfig(verbose: Boolean) {

    class AwsProfile(val name: String, verbose: Boolean) {
      private val properties = collection.mutable.Map.empty[String, String]
      def addProperty(key: String, value: String): Unit = {
        properties.update(key, value)
        if (verbose) println(s"${CYAN}$key${RESET}${BLUE}=${RESET}${WHITE}$value${RESET}")
      }
      def getProperty(key: String): Option[String] = {
        properties.get(key)
      }
    }

    private val profiles = collection.mutable.Map.empty[String, AwsProfile]

    private var currentProfile: AwsProfile = new AwsProfile("default", verbose)
    profiles.update("default", currentProfile)

    def startProfile(name: String): AwsConfig = {
      currentProfile = new AwsProfile(name, verbose)
      profiles.update(name, currentProfile)
      if (verbose) println(s"${GREEN}[profile ${BOLD}$name${RESET}${GREEN}]${RESET}")
      this
    }

    def addProperty(key: String, value: String): AwsConfig = {
      currentProfile.addProperty(key, value)
      this
    }

    def getProfile(profileName: String): Option[AwsProfile] =
      profiles.get(profileName)

    def getProfileProperty(profileName: String, key: String): Option[String] =
      profiles
        .get(profileName)
        .flatMap(profileConfig =>
          profileConfig.getProperty(key).orElse {
            profileConfig
              .getProperty("source_profile")
              .flatMap { sourceProfileName =>
                getProfileProperty(sourceProfileName, key)
              }
          }
        )

    def getSourceProfileOf(profileName: String, key: String): Option[String] =
      profiles
        .get(profileName)
        .flatMap(profileConfig =>
          profileConfig
            .getProperty(key)
            .map(_ => profileName)
            .orElse {
              profileConfig
                .getProperty("source_profile")
                .flatMap { sourceProfileName =>
                  getSourceProfileOf(sourceProfileName, key)
                }
            }
        )
  }

}
