/*
 * Copyright (C) 2014 - 2016 Softwaremill <http://softwaremill.com>
 * Copyright (C) 2016 - 2019 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.kafka.internal

import akka.actor.{ActorRef, ExtendedActorSystem, Terminated}
import akka.annotation.InternalApi
import akka.kafka.Subscriptions._
import akka.kafka.{AutoSubscription, ConsumerSettings, ManualSubscription, Subscription}
import akka.stream.{ActorMaterializerHelper, SourceShape}
import org.apache.kafka.common.TopicPartition

import scala.concurrent.{Future, Promise}

/**
 * Internal API.
 *
 * Anonymous sub-class instances are created in [[CommittableSource]] and [[TransactionalSource]].
 */
@InternalApi private abstract class SingleSourceLogic[K, V, Msg](
    shape: SourceShape[Msg],
    settings: ConsumerSettings[K, V],
    subscription: Subscription
) extends BaseSingleSourceLogic[K, V, Msg](shape) {

  override protected def logSource: Class[_] = classOf[SingleSourceLogic[K, V, Msg]]

  private val consumerPromise = Promise[ActorRef]
  final val actorNumber = KafkaConsumerActor.Internal.nextNumber()
  final def consumerFuture: Future[ActorRef] = consumerPromise.future

  final def configureSubscription(): Unit = {

    def rebalanceListener(autoSubscription: AutoSubscription): KafkaConsumerActor.ListenerCallbacks = {
      val assignedCB = getAsyncCallback[Set[TopicPartition]](partitionAssignedHandler)

      val revokedCB = getAsyncCallback[Set[TopicPartition]](partitionRevokedHandler)

      KafkaConsumerActor.ListenerCallbacks(autoSubscription,
                                           sourceActor.ref,
                                           assignedCB,
                                           revokedCB,
                                           blockingRevokedHandler)
    }

    subscription match {
      case sub @ TopicSubscription(topics, _) =>
        consumerActor.tell(KafkaConsumerActor.Internal.Subscribe(topics, rebalanceListener(sub)), sourceActor.ref)
      case sub @ TopicSubscriptionPattern(topics, _) =>
        consumerActor.tell(KafkaConsumerActor.Internal.SubscribePattern(topics, rebalanceListener(sub)),
                           sourceActor.ref)
      case s: ManualSubscription => configureManualSubscription(s)
    }

  }

  final def createConsumerActor(): ActorRef = {
    val extendedActorSystem = ActorMaterializerHelper.downcast(materializer).system.asInstanceOf[ExtendedActorSystem]
    val actor =
      extendedActorSystem.systemActorOf(akka.kafka.KafkaConsumerActor.props(sourceActor.ref, settings),
                                        s"kafka-consumer-$actorNumber")
    consumerPromise.success(actor)
    actor
  }

  final override def postStop(): Unit = {
    consumerActor.tell(KafkaConsumerActor.Internal.Stop, sourceActor.ref)
    super.postStop()
  }

  final def performShutdown(): Unit = {
    setKeepGoing(true)
    if (!isClosed(shape.out)) {
      complete(shape.out)
    }
    sourceActor.become(shuttingDownReceive)
    stopConsumerActor()
  }

  protected def shuttingDownReceive: PartialFunction[(ActorRef, Any), Unit] = {
    case (_, Terminated(ref)) if ref == consumerActor =>
      onShutdown()
      completeStage()
  }

  protected def stopConsumerActor(): Unit =
    materializer.scheduleOnce(settings.stopTimeout, new Runnable {
      override def run(): Unit =
        consumerActor.tell(KafkaConsumerActor.Internal.Stop, sourceActor.ref)
    })

  protected def partitionAssignedHandler(assignedTps: Set[TopicPartition]): Unit = {
    tps ++= assignedTps
    log.debug("Assigned partitions: {}. All partitions: {}", assignedTps, tps)
    requestMessages()
  }

  protected def partitionRevokedHandler(revokedTps: Set[TopicPartition]): Unit = {
    tps --= revokedTps
    log.debug("Revoked partitions: {}. All partitions: {}", revokedTps, tps)
  }

  protected def blockingRevokedHandler(revokedTps: Set[TopicPartition]): Unit = {}
}
