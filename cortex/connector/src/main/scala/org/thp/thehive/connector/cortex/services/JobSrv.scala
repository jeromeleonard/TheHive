package org.thp.thehive.connector.cortex.services

import gremlin.scala._
import javax.inject.{Inject, Singleton}
import org.thp.cortex.client.CortexConfig
import org.thp.cortex.dto.client.{CortexAttachment, InputCortexArtifact}
import org.thp.scalligraph.EntitySteps
import org.thp.cortex.client.{CortexClient, CortexConfig}
import org.thp.scalligraph.{EntitySteps, NotFoundError}
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.{BaseVertexSteps, Database, Entity}
import org.thp.scalligraph.services._
import org.thp.thehive.connector.cortex.models.{Job, ObservableJob}
import org.thp.thehive.models._
import scala.concurrent.{ExecutionContext, Future}
import io.scalaland.chimney.dsl._

import org.thp.cortex.client.models.Artifact

@Singleton
class JobSrv @Inject()(implicit db: Database, cortexConfig: CortexConfig, implicit val ex: ExecutionContext) extends VertexSrv[Job, JobSteps] {

  val observableJobSrv = new EdgeSrv[ObservableJob, Observable, Job]

  override def steps(raw: GremlinScala[Vertex])(implicit graph: Graph): JobSteps = new JobSteps(raw)

  /**
    * Creates a Job with with according ObservableJob edge
    *
    * @param job the job date to create
    * @param observable the related observable
    * @param graph the implicit graph instance needed
    * @param authContext the implicit auth needed
    * @return
    */
  def create(job: Job, observable: Observable with Entity)(implicit graph: Graph, authContext: AuthContext): Job with Entity = {
    val createdJob = create(job)
    observableJobSrv.create(ObservableJob(), observable, createdJob)

    createdJob
  }

  def richObservableToArtifact(richObservable: RichObservable) =
    richObservable.into[Artifact].transform

  def submitJob(job: Job, observable: RichObservable, `case`: Case with Entity) =
    for {
      cortexClient ← cortexConfig
        .instances
        .find(_.name == job.cortexId)
        .fold[Future[CortexClient]](Future.failed(NotFoundError(s"Cortex ${job.cortexId} not found")))(Future.successful)
      analyzer ← cortexClient.getAnalyzer(job.workerId)
      artifact = richObservableToArtifact(observable)
      job ← cortexClient.analyse(analyzer.id, artifact)

      cortexArtifact ← (observable.attachment, observable.data) match {
        case (None, Some(data)) ⇒
          Future.successful(Artifact(observable.tlp, `case`.pap, observable.`type`, `case`._id, Some(data.data), None))
        case (Some(attachment), None) ⇒
          Future.successful(Artifact(observable.tlp, `case`.pap, observable.`type`, `case`._id, None, None)) // TODO
        case _ ⇒ Future.failed(new Exception(s"Invalid Observable data for ${observable.observable._id}"))
      }
    } yield "todo"

}

@EntitySteps[Job]
class JobSteps(raw: GremlinScala[Vertex])(implicit db: Database, graph: Graph) extends BaseVertexSteps[Job, JobSteps](raw) {

  /**
    * Checks if a Job is visible from a certain UserRole end
    *
    * @param authContext the auth context to check login against
    * @return
    */
  def visible(implicit authContext: AuthContext): JobSteps = newInstance(
    raw.filter(
      _.inTo[ObservableJob]
        .inTo[ShareObservable]
        .inTo[OrganisationShare]
        .inTo[RoleOrganisation]
        .inTo[UserRole]
        .has(Key("login") of authContext.userId)
    )
  )

  override def newInstance(raw: GremlinScala[Vertex]): JobSteps = new JobSteps(raw)
}