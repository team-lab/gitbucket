package service

import model.Profile._
import profile.simple._
import model.{WebHook, Account, Issue, PullRequest, IssueComment, Repository, CommitStatus, CommitState}
import org.slf4j.LoggerFactory
import service.RepositoryService.RepositoryInfo
import util.JGitUtil
import util.JGitUtil.CommitInfo
import org.eclipse.jgit.api.Git
import org.apache.http.message.BasicNameValuePair
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.NameValuePair
import java.util.Date
import api._

trait WebHookService {
  import WebHookService._

  private val logger = LoggerFactory.getLogger(classOf[WebHookService])

  def getWebHookURLs(owner: String, repository: String)(implicit s: Session): List[WebHook] =
    WebHooks.filter(_.byRepository(owner, repository)).sortBy(_.url).list

  def addWebHookURL(owner: String, repository: String, url :String)(implicit s: Session): Unit =
    WebHooks insert WebHook(owner, repository, url)

  def deleteWebHookURL(owner: String, repository: String, url :String)(implicit s: Session): Unit =
    WebHooks.filter(_.byPrimaryKey(owner, repository, url)).delete

  def callWebHookOf(owner: String, repository: String, eventName: String)(makePayload: => Option[WebHookPayload])(implicit s: Session, c: JsonFormat.Context): Unit = {
    val webHookURLs = getWebHookURLs(owner, repository)
    if(webHookURLs.nonEmpty){
      makePayload.map(callWebHook(eventName, webHookURLs, _))
    }
  }

  def callWebHook(eventName: String, webHookURLs: List[WebHook], payload: WebHookPayload)(implicit c: JsonFormat.Context): Unit = {
    import org.apache.http.client.methods.HttpPost
    import org.apache.http.impl.client.HttpClientBuilder
    import scala.concurrent._
    import ExecutionContext.Implicits.global

    if(webHookURLs.nonEmpty){
      val json = JsonFormat(payload)
      val httpClient = HttpClientBuilder.create.build

      webHookURLs.foreach { webHookUrl =>
        val f = Future {
          logger.debug(s"start web hook invocation for ${webHookUrl}")
          val httpPost = new HttpPost(webHookUrl.url)
          httpPost.addHeader("X-Github-Event", eventName)

          val params: java.util.List[NameValuePair] = new java.util.ArrayList()
          params.add(new BasicNameValuePair("payload", json))
          httpPost.setEntity(new UrlEncodedFormEntity(params, "UTF-8"))

          httpClient.execute(httpPost)
          httpPost.releaseConnection()
          logger.debug(s"end web hook invocation for ${webHookUrl}")
        }
        f.onSuccess {
          case s => logger.debug(s"Success: web hook request to ${webHookUrl.url}")
        }
        f.onFailure {
          case t => logger.error(s"Failed: web hook request to ${webHookUrl.url}", t)
        }
      }
    }
    logger.debug("end callWebHook")
  }
}


trait WebHookPullRequestService extends WebHookService {
  self: AccountService with RepositoryService with PullRequestService with IssuesService =>

  import WebHookService._
  // https://developer.github.com/v3/activity/events/types/#issuesevent
  def callIssuesWebHook(action: String, repository: RepositoryService.RepositoryInfo, issue: Issue, baseUrl: String, sender: model.Account)(implicit s: Session, context:JsonFormat.Context): Unit = {
    callWebHookOf(repository.owner, repository.name, "issues"){
      val users = getAccountsByUserNames(Set(repository.owner, issue.userName), Set(sender))
      for{
        repoOwner <- users.get(repository.owner)
        issueUser <- users.get(issue.userName)
      } yield {
        WebHookIssuesPayload(
          action       = action,
          number       = issue.issueId,
          repository   = ApiRepository(repository, ApiUser(repoOwner)),
          issue        = ApiIssue(issue, ApiUser(issueUser)),
          sender       = ApiUser(sender))
      }
    }
  }

  def callPullRequestWebHook(action: String, repository: RepositoryService.RepositoryInfo, issueId: Int, baseUrl: String, sender: model.Account)(implicit s: Session, context:JsonFormat.Context): Unit = {
    import WebHookService._
    callWebHookOf(repository.owner, repository.name, "pull_request"){
      for{
        (issue, pullRequest) <- getPullRequest(repository.owner, repository.name, issueId)
        users = getAccountsByUserNames(Set(repository.owner, pullRequest.requestUserName), Set(sender))
        baseOwner <- users.get(repository.owner)
        headOwner <- users.get(pullRequest.requestUserName)
        headRepo  <- getRepository(pullRequest.requestUserName, pullRequest.requestRepositoryName, baseUrl)
      } yield {
        WebHookPullRequestPayload(
          action         = action,
          issue          = issue,
          pullRequest    = pullRequest,
          headRepository = headRepo,
          headOwner      = headOwner,
          baseRepository = repository,
          baseOwner      = baseOwner,
          sender         = sender)
      }
    }
  }

  def getPullRequestsByRequestForWebhook(userName:String, repositoryName:String, branch:String)
                                       (implicit s: Session): Map[(Issue, PullRequest, Account, Account), List[WebHook]] =
    (for{
      is <- Issues if is.closed    === false.bind
      pr <- PullRequests if pr.byPrimaryKey(is.userName, is.repositoryName, is.issueId)
      if pr.requestUserName        === userName.bind
      if pr.requestRepositoryName  === repositoryName.bind
      if pr.requestBranch          === branch.bind
      bu <- Accounts if bu.userName === pr.userName
      ru <- Accounts if ru.userName === pr.requestUserName
      wh <- WebHooks if wh.byRepository(is.userName , is.repositoryName)
    } yield {
      ((is, pr, bu, ru), wh)
    }).list.groupBy(_._1).mapValues(_.map(_._2))

  def callPullRequestWebHookByRequestBranch(action: String, requestRepository: RepositoryService.RepositoryInfo, requestBranch: String, baseUrl: String, sender: model.Account)(implicit s: Session, context:JsonFormat.Context): Unit = {
    import WebHookService._
    for{
      ((issue, pullRequest, baseOwner, headOwner), webHooks) <- getPullRequestsByRequestForWebhook(requestRepository.owner, requestRepository.name, requestBranch)
      baseRepo <- getRepository(pullRequest.userName, pullRequest.repositoryName, baseUrl)
    } yield {
      val payload = WebHookPullRequestPayload(
        action         = action,
        issue          = issue,
        pullRequest    = pullRequest,
        headRepository = requestRepository,
        headOwner      = headOwner,
        baseRepository = baseRepo,
        baseOwner      = baseOwner,
        sender         = sender)
      callWebHook("pull_request", webHooks, payload)
    }
  }
}

trait WebHookIssueCommentService extends WebHookPullRequestService {
  self: AccountService with RepositoryService with PullRequestService with IssuesService =>

  import WebHookService._
  def callIssueCommentWebHook(repository: RepositoryService.RepositoryInfo, issue: Issue, issueCommentId: Int, sender: model.Account)(implicit s: Session, context:JsonFormat.Context): Unit = {
    callWebHookOf(repository.owner, repository.name, "issue_comment"){
      for{
        issueComment <- getComment(repository.owner, repository.name, issueCommentId.toString())
        users = getAccountsByUserNames(Set(issue.userName, repository.owner, issueComment.userName), Set(sender))
        issueUser <- users.get(issue.userName)
        repoOwner <- users.get(repository.owner)
        commenter <- users.get(issueComment.userName)
      } yield {
        WebHookIssueCommentPayload(
          issue          = issue,
          issueUser      = issueUser,
          comment        = issueComment,
          commentUser    = commenter,
          repository     = repository,
          repositoryUser = repoOwner,
          sender         = sender)
      }
    }
  }
}

object WebHookService {
  trait WebHookPayload

  // https://developer.github.com/v3/activity/events/types/#pushevent
  case class WebHookPushPayload(
    pusher: ApiUser,
    ref: String,
    commits: List[ApiCommit],
    repository: ApiRepository
  ) extends WebHookPayload

  object WebHookPushPayload {
    def apply(git: Git, pusher: Account, refName: String, repositoryInfo: RepositoryInfo,
              commits: List[CommitInfo], repositoryOwner: Account): WebHookPushPayload =
      WebHookPushPayload(
        ApiUser(pusher),
        refName,
        commits.map{ commit => ApiCommit(git, util.RepositoryName(repositoryInfo), commit) },
        ApiRepository(
          repositoryInfo,
          owner= ApiUser(repositoryOwner)
        )
      )
  }

  // https://developer.github.com/v3/activity/events/types/#issuesevent
  case class WebHookIssuesPayload(
    action: String,
    number: Int,
    repository: ApiRepository,
    issue: ApiIssue,
    sender: ApiUser) extends WebHookPayload

  // https://developer.github.com/v3/activity/events/types/#pullrequestevent
  case class WebHookPullRequestPayload(
    action: String,
    number: Int,
    repository: ApiRepository,
    pull_request: ApiPullRequest,
    sender: ApiUser
  ) extends WebHookPayload

  object WebHookPullRequestPayload{
    def apply(action: String,
        issue: Issue,
        pullRequest: PullRequest,
        headRepository: RepositoryInfo,
        headOwner: Account,
        baseRepository: RepositoryInfo,
        baseOwner: Account,
        sender: model.Account): WebHookPullRequestPayload = {
      val headRepoPayload = ApiRepository(headRepository, headOwner)
      val baseRepoPayload = ApiRepository(baseRepository, baseOwner)
      val senderPayload = ApiUser(sender)
      val pr = ApiPullRequest(issue, pullRequest, headRepoPayload, baseRepoPayload, senderPayload)
      WebHookPullRequestPayload(
        action       = action,
        number       = issue.issueId,
        repository   = pr.base.repo,
        pull_request = pr,
        sender       = senderPayload
      )
    }
  }

  // https://developer.github.com/v3/activity/events/types/#issuecommentevent
  case class WebHookIssueCommentPayload(
    action: String,
    repository: ApiRepository,
    issue: ApiIssue,
    comment: ApiComment,
    sender: ApiUser
  ) extends WebHookPayload

  object WebHookIssueCommentPayload{
    def apply(
        issue: Issue,
        issueUser: Account,
        comment: IssueComment,
        commentUser: Account,
        repository: RepositoryInfo,
        repositoryUser: Account,
        sender: Account): WebHookIssueCommentPayload =
      WebHookIssueCommentPayload(
        action       = "created",
        repository   = ApiRepository(repository, repositoryUser),
        issue        = ApiIssue(issue, ApiUser(issueUser)),
        comment      = ApiComment(comment, ApiUser(commentUser)),
        sender       = ApiUser(sender))
  }
}
