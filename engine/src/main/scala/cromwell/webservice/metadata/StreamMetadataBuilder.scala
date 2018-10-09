package cromwell.webservice.metadata

import cats.effect.IO
import cromwell.services.metadata.{MetadataEvent, MetadataKey, MetadataValue}
import slick.basic.DatabasePublisher
import fs2.interop.reactivestreams._
import MetadataComponent._
import com.typesafe.scalalogging.StrictLogging
import cromwell.core.WorkflowId

import scala.concurrent.ExecutionContext
import spray.json._

object StreamMetadataBuilder extends StrictLogging {
  def build(databasePublisher: DatabasePublisher[MetadataEvent], workflowId: WorkflowId)(implicit ec: ExecutionContext): JsObject = {
    implicit val contextShift = IO.contextShift(ec)
    val subscriber = StreamSubscriber[IO, MetadataEvent].unsafeRunSync()
    databasePublisher.subscribe(subscriber)
    
    val workflowIdEvent = MetadataEvent(
      MetadataKey(workflowId, None, "id"),
      MetadataValue(workflowId.toString)
    )
//    def toComponentAsync(e: MetadataEvent) = for {
//      _ <- IO.shift
//      component <- IO { toMetadataComponent(Map.empty)(e) }
//    } yield component
    
    val stream = (subscriber.stream ++ fs2.Stream.emit(workflowIdEvent))
      .prefetchN(10000)
      .balance(10000)
      .take(10)
      .map(_.map(toMetadataComponent(Map.empty)))
      .map(_.reduce(MetadataComponentMonoid.combine))
      .flatten
      .reduce(MetadataComponentMonoid.combine)
      .map({e => logger.info("Combined finished"); e})
      .map(_.toJson.asJsObject)

    logger.info("Ready")
    val r = stream.compile.toVector.unsafeRunSync().head
    logger.info("Done")
    r
  }
}
