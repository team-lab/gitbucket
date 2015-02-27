package service
import org.specs2.mutable.Specification
import java.util.Date
import model._
import model.Profile._
import profile.simple._
class RepositoryServiceSpec extends Specification with ServiceSpecBase with RepositoryService with AccountService{
  def generateNewAccount(name:String)(implicit s:Session):Account = {
    createAccount(name, name, name, s"${name}@example.com", false, None)
    getAccountByUserName(name).get
  }
  "RepositoryService" should {
    "renameRepository can rename CommitState" in { withTestDB { implicit session =>
      val tester = generateNewAccount("tester")
      createRepository("repo","root",None,false)
      val commitStatusService = new CommitStatusService{}
      val id = commitStatusService.createCommitStatus(
        userName    = "root",
        repositoryName = "repo",
        sha         = "0e97b8f59f7cdd709418bb59de53f741fd1c1bd7",
        context     = "jenkins/test",
        state       = CommitState.PENDING,
        targetUrl   = Some("http://example.com/target"),
        description = Some("description"),
        creator     = tester,
        now         = new java.util.Date)
      val org = commitStatusService.getCommitStatus("root","repo", id).get
      renameRepository("root","repo","tester","repo2")
	  val neo = commitStatusService.getCommitStatus("tester","repo2", org.commitId, org.context).get
      neo must_==
        org.copy(
          commitStatusId=neo.commitStatusId,
          repositoryName="repo2",
          userName="tester")
    }}
  }
}
