package notification.controllers

import models.TopicTypes.{Breaking, TagSeries}
import java.util.UUID
import models._
import notification.{DateTimeFreezed, NotificationsFixtures}
import notification.models.{Push, PushResult}
import notification.services.frontend.FrontendAlerts
import notification.services._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.JsonMatchers
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.test.PlaySpecification
import tracking.InMemoryNotificationReportRepository

import scala.concurrent.Future
import notification.authentication.NotificationAuthAction
import notification.services.azure.NewsstandSender
import play.api.mvc.DefaultControllerComponents
import play.api.test.Helpers.stubControllerComponents
import cats.instances.future._
import cats.syntax.either._


class MainSpec(implicit ec: ExecutionEnv) extends PlaySpecification with Mockito with JsonMatchers with DateTimeFreezed {
  "Sending notification to topics" should {
    "successfully send a notification to multiple topics" in new MainScope {
      val request = requestWithValidTopics
      val response = main.pushTopics()(request)

      status(response) must equalTo(CREATED)
      pushSent must beSome.which(_.destination must beEqualTo(Left(validTopics)))
    }
    "refuse a notification with an invalid key" in new MainScope {
      val request = invalidAuthenticatedRequest.withBody(breakingNewsNotification(validTopics))
      val response = main.pushTopics()(request)

      status(response) must equalTo(UNAUTHORIZED)
    }
    "refuse a notification with an election-only key" in new MainScope {
      val request = electionsAuthenticatedRequest.withBody(breakingNewsNotification(validTopics))
      val response = main.pushTopics()(request)

      status(response) must equalTo(UNAUTHORIZED)
    }
    "successfully send a notification to multiple election topics with an election key" in new MainScope {
      val request = electionsAuthenticatedRequest.withBody(breakingNewsNotification(validElectionTopics))
      val response = main.pushTopics()(request)

      status(response) must equalTo(CREATED)
      pushSent must beSome.which(_.destination must beEqualTo(Left(validElectionTopics)))
    }
    "refuse a notification that is sent twice" in new MainScope {
      val request = requestWithValidTopics
      val firstResponse = main.pushTopics()(request)
      status(firstResponse) must equalTo(CREATED)
      val secondResponse = main.pushTopics()(request)
      status(secondResponse) must equalTo(BAD_REQUEST)
    }
    "refuse a notification without a topic" in new MainScope {
      val request = authenticatedRequest.withBody(breakingNewsNotification(Set()))
      status(main.pushTopics()(request)) must equalTo(BAD_REQUEST)
    }
    "refuse a notification with too many topics" in new MainScope {
      val topics = (1 to 21).map(i => Topic(Breaking, s"$i"))
      val request = authenticatedRequest.withBody(breakingNewsNotification(Set()))
      status(main.pushTopics()(request)) must equalTo(BAD_REQUEST)
    }
  }

  "Sending correct notification" should {
    "notify reporting repository about added notifications" in new MainScope {
      val request = requestWithValidTopics
      val expectedReport = reportWithSenderReports(List(
        senderReport(Senders.AzureNotificationsHub), senderReport(Senders.FrontendAlerts)
      ))

      val response = main.pushTopics()(request)

      status(response) must equalTo(CREATED)
      reportRepository.getByUuid(expectedReport.notification.id) must equalTo(Right(expectedReport)).await
    }

    "report frontend alerts rejected notifications" in new MainScope {
      val request = requestWithValidTopics
      frontendAlerts.sendNotification(any) returns Future.successful(Left(NotificationRejected(Some(providerError))))

      val response = main.pushTopics()(request)

      status(response) must equalTo(CREATED)
      contentAsJson(response).as[PushResult].rejectedNotifications must beSome.which(_.length == 1)
    }

    "send notification to frontend news alerts" in new MainScope {
      val request = requestWithValidTopics

      val response = main.pushTopics()(request)

      status(response) must equalTo(CREATED)
      there was one(frontendAlerts).sendNotification(any)
    }


    "notification report has sent time of last sender report" in new MainScope {
      val request = requestWithValidTopics
      val frontendAlertsReport = senderReport(Senders.FrontendAlerts, sentTimeOffsetSeconds = 1)
      frontendAlerts.sendNotification(any) returns Future.successful(Right(frontendAlertsReport))

      val response = main.pushTopics()(request)


      status(response) must equalTo(CREATED)

      val sentTime = reportRepository.getByUuid(breakingNewsNotification(Set.empty).id).map(_.map(_.sentTime))

      sentTime must beEqualTo(Right(frontendAlertsReport.sentTime)).await
    }
  }

  "Sending notification for newsstand" should {
    "successfully send a notification for newsstand" in new MainScope {
      val request = authenticatedRequest
      val id = UUID.randomUUID()
      val response = main.pushNewsstand()(request)

      status(response) must equalTo(CREATED)

      there was one(newsstandNotificationSender).sendNotification(any)
    }

    "refuse a newsstand notification with an invalid key" in new MainScope {
      val request = invalidAuthenticatedRequest
      val response = main.pushNewsstand()(request)

      status(response) must equalTo(UNAUTHORIZED)
    }
  }

  trait NotificationSenderSupportScope extends Scope {
    self: NotificationsFixtures =>
    var pushSent: Option[Push] = None

    val newsstandNotificationSender = mock[NewsstandSender]
    newsstandNotificationSender.sendNotification(any[UUID]) returns Future.successful(Right(Some("")))
    val windowsNotificationSender = {
      new NotificationSender {
        override def sendNotification(push: Push): Future[SenderResult] = {
          pushSent = Some(push)
          Future.successful(Right(senderReport(Senders.AzureNotificationsHub)))
        }
      }
    }
  }

  trait FrontendAlertsScope extends Scope {
    self: NotificationsFixtures =>
    val frontendAlerts = mock[FrontendAlerts]
    frontendAlerts.sendNotification(any) returns Future.successful(Right(senderReport(Senders.FrontendAlerts, None)))
  }

  trait MainScope extends Scope
    with NotificationSenderSupportScope
    with FrontendAlertsScope
    with NotificationsFixtures {
    val conf: Configuration = {
      val m = mock[Configuration]
      m.apiKeys returns List(apiKey)
      m.electionRestrictedApiKeys returns List(electionsApiKey)
      m
    }

    val controllerComponents = stubControllerComponents()
    val reportRepository = new InMemoryNotificationReportRepository
    val authAction = new NotificationAuthAction(conf, controllerComponents)

    val main = new Main(
      configuration = conf,
      senders = List(windowsNotificationSender, frontendAlerts),
      newsstandSender = newsstandNotificationSender,
    notificationReportRepository = reportRepository,
      controllerComponents = controllerComponents,
      authAction = authAction
    )
  }

}
