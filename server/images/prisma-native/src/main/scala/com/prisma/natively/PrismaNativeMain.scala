package com.prisma.natively

import java.io.PrintWriter
import java.sql.DriverManager

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.prisma.image.{SangriaHandlerImpl, Version}
import com.prisma.sangria_server.BlazeSangriaServer

object PrismaNativeMain {
  System.setProperty("org.jooq.no-logo", "true")

  def main(args: Array[String]): Unit = {
    implicit val system       = ActorSystem("prisma", StaticAkkaConfig.config)
    implicit val materializer = ActorMaterializer()
    implicit val dependencies = PrismaNativeDependencies()

    DriverManager.setLogWriter(new PrintWriter(System.out))

    dependencies.initialize()(system.dispatcher)
    dependencies.migrator.initialize

    Version.check()

//    ServerExecutor(
//      port = dependencies.config.port.getOrElse(4466),
//      ManagementServer("management"),
//      WebsocketServer(dependencies),
//      ApiServer(dependencies.apiSchemaBuilder),
//      WorkerServer(dependencies)
//    ).startBlocking()

    val sangriaHandler = SangriaHandlerImpl(managementApiEnabled = true)
    val executor       = BlazeSangriaServer
    val server         = executor.create(handler = sangriaHandler, port = dependencies.config.port.getOrElse(4466), requestPrefix = sys.env.getOrElse("ENV", "local"))
    server.startBlocking()
  }
}
