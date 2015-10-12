package tracking

import models.NotificationReport
import org.joda.time.DateTime
import tracking.Repository.RepositoryResult
import scala.concurrent.Future

trait SentNotificationReportRepository {

  def store(report: NotificationReport): Future[RepositoryResult[Unit]]

  def getByUuid(uuid: String): Future[RepositoryResult[NotificationReport]]

  def getByTypeWithDateRange(notificationType: String, from: DateTime, to: DateTime): Future[RepositoryResult[List[NotificationReport]]]
}