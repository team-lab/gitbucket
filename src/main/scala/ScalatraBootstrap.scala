import _root_.servlet.{BasicAuthenticationFilter, TransactionFilter, AccessTokenAuthenticationFilter}
import app._
import plugin.PluginRegistry

//import jp.sf.amateras.scalatra.forms.ValidationJavaScriptProvider
import org.scalatra._
import javax.servlet._
import java.util.EnumSet

class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext) {
    // Register TransactionFilter and BasicAuthenticationFilter at first
    context.addFilter("transactionFilter", new TransactionFilter)
    context.getFilterRegistration("transactionFilter").addMappingForUrlPatterns(EnumSet.allOf(classOf[DispatcherType]), true, "/*")
    context.addFilter("basicAuthenticationFilter", new BasicAuthenticationFilter)
    context.getFilterRegistration("basicAuthenticationFilter").addMappingForUrlPatterns(EnumSet.allOf(classOf[DispatcherType]), true, "/git/*")
    context.addFilter("accessTokenAuthenticationFilter", new AccessTokenAuthenticationFilter)
    context.getFilterRegistration("accessTokenAuthenticationFilter").addMappingForUrlPatterns(EnumSet.allOf(classOf[DispatcherType]), true, "/api/v3/*")
    // Register controllers
    context.mount(new AnonymousAccessController, "/*")

    PluginRegistry().getControllers.foreach { case (controller, path) =>
      context.mount(controller, path)
    }

    context.mount(new IndexController, "/")
    context.mount(new SearchController, "/")
    context.mount(new FileUploadController, "/upload")
    context.mount(new DashboardController, "/*")
    context.mount(new UserManagementController, "/*")
    context.mount(new SystemSettingsController, "/*")
    context.mount(new AccountController, "/*")
    context.mount(new RepositoryViewerController, "/*")
    context.mount(new WikiController, "/*")
    context.mount(new LabelsController, "/*")
    context.mount(new MilestonesController, "/*")
    context.mount(new IssuesController, "/*")
    context.mount(new PullRequestsController, "/*")
    context.mount(new RepositorySettingsController, "/*")

    // Create GITBUCKET_HOME directory if it does not exist
    val dir = new java.io.File(_root_.util.Directory.GitBucketHome)
    if(!dir.exists){
      dir.mkdirs()
    }
  }
}