package service

import model.Profile._
import profile.simple._
import util.ControlUtil._
import util.DatabaseConfig
import java.sql.DriverManager
import org.apache.commons.io.FileUtils
import scala.util.Random
import java.io.File
import model._

trait ServiceSpecBase {

  def withTestDB[A](action: (Session) => A): A = {
    util.FileUtil.withTmpDir(new File(FileUtils.getTempDirectory(), Random.alphanumeric.take(10).mkString)){ dir =>
      val (url, user, pass) = (DatabaseConfig.url(Some(dir.toString)), DatabaseConfig.user, DatabaseConfig.password)
      org.h2.Driver.load()
      using(DriverManager.getConnection(url, user, pass)){ conn =>
        servlet.AutoUpdate.versions.reverse.foreach(_.update(conn, Thread.currentThread.getContextClassLoader))
      }
      Database.forURL(url, user, pass).withSession { session =>
        action(session)
      }
    }
  }

  def generateNewAccount(name:String)(implicit s:Session):Account = {
    AccountService.createAccount(name, name, name, s"${name}@example.com", false, None)
    AccountService.getAccountByUserName(name).get
  }

  lazy val dummyService = new RepositoryService with AccountService with IssuesService with PullRequestService (){}

  def generateNewUserWithDBRepository(userName:String, repositoryName:String)(implicit s:Session):Account = {
    val ac = generateNewAccount(userName)
    dummyService.createRepository(repositoryName, userName, None, false)
    ac
  }
  def generateNewPullRequest(base:String, request:String)(implicit s:Session):(Issue, PullRequest) = {
    val Array(baseUserName, baseRepositoryName, baesBranch)=base.split("/")
    val Array(requestUserName, requestRepositoryName, requestBranch)=request.split("/")
      val issueId = dummyService.createIssue(
      owner            = baseUserName,
      repository       = baseRepositoryName,
      loginUser        = requestUserName,
      title            = "issue title",
      content          = None,
      assignedUserName = None,
      milestoneId      = None,
      isPullRequest    = true)

    dummyService.createPullRequest(
      originUserName        = baseUserName,
      originRepositoryName  = baseRepositoryName,
      issueId               = issueId,
      originBranch          = baesBranch,
      requestUserName       = requestUserName,
      requestRepositoryName = requestRepositoryName,
      requestBranch         = requestBranch,
      commitIdFrom          = baesBranch,
      commitIdTo            = requestBranch)
    dummyService.getPullRequest(baseUserName, baseRepositoryName, issueId).get
  }
}
