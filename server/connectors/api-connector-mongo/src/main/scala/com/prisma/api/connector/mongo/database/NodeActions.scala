package com.prisma.api.connector.mongo.database

import com.prisma.api.connector._
import com.prisma.api.connector.mongo.extensions.FieldCombinators._
import com.prisma.api.connector.mongo.extensions.GCBisonTransformer.GCValueBsonTransformer
import com.prisma.api.connector.mongo.extensions.NodeSelectorBsonTransformer._
import com.prisma.api.schema.APIErrors
import com.prisma.api.schema.APIErrors.{FieldCannotBeNull, NodesNotConnectedError}
import com.prisma.gc_values._
import com.prisma.shared.models.RelationField
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.bson.{BsonArray, BsonValue, conversions}
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.UpdateOptions
import org.mongodb.scala.model.Updates._
import org.mongodb.scala.{Document, MongoCollection}

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

trait NodeActions extends NodeSingleQueries {

  //region Top Level

  def createNode(mutaction: CreateNode, includeRelayRow: Boolean)(implicit ec: ExecutionContext): SimpleMongoAction[MutactionResults] =
    SimpleMongoAction { database =>
      val collection: MongoCollection[Document]                                               = database.getCollection(mutaction.model.dbName)
      val (id: IdGCValue, docWithId: Document, childResults: Vector[DatabaseMutactionResult]) = createToDoc(mutaction)

      collection.insertOne(docWithId).toFuture().map(_ => MutactionResults(Vector(CreateNodeResult(id, mutaction)) ++ childResults))
    }

  def deleteNode(mutaction: TopLevelDeleteNode)(implicit ec: ExecutionContext): SimpleMongoAction[MutactionResults] = SimpleMongoAction { database =>
    val collection: MongoCollection[Document]      = database.getCollection(mutaction.model.name)
    val previousValues: Future[Option[PrismaNode]] = getNodeByWhere(mutaction.where, SelectedFields.all(mutaction.model), database)

    previousValues.flatMap {
      case Some(node) => collection.deleteOne(mutaction.where).toFuture().map(_ => MutactionResults(Vector(DeleteNodeResult(node.id, node, mutaction))))
      case None       => throw APIErrors.NodeNotFoundForWhereError(mutaction.where)
    }
  }

  def deleteNodes(mutaction: DeleteNodes, shouldDeleteRelayIds: Boolean)(implicit ec: ExecutionContext): SimpleMongoAction[MutactionResults] =
    SimpleMongoAction { database =>
      val collection                        = database.getCollection(mutaction.model.name)
      val futureIds: Future[Seq[IdGCValue]] = getNodeIdsByFilter(mutaction.model, mutaction.whereFilter, database)

      futureIds.flatMap(ids => collection.deleteMany(in("_id", ids.map(_.value): _*)).toFuture().map(_ => MutactionResults(Vector.empty)))
    }

  def updateNode(mutaction: TopLevelUpdateNode)(implicit ec: ExecutionContext): SimpleMongoAction[MutactionResults] = SimpleMongoAction { database =>
    val collection: MongoCollection[Document]      = database.getCollection(mutaction.model.name)
    val previousValues: Future[Option[PrismaNode]] = getNodeByWhere(mutaction.where, database)

    previousValues.flatMap {
      case None =>
        throw APIErrors.NodeNotFoundForWhereError(mutaction.where)

      case Some(node) =>
        val scalarUpdates = toOneScalarUpdateValues(mutaction)

        //  combine this into one function
        val (nestedCreateResults: Vector[DatabaseMutactionResult], nestedCreateDocs: Map[String, BsonValue]) =
          nestedCreateDocsAndResults(mutaction.nestedCreates)
        val nestedCreates: Vector[Bson] = nestedCreateDocs.map { case (f, v) => set(f, v) }.toVector

        val (nestedDeletes, nestedDeleteResults) = embeddedNestedDeleteActionsAndResults(node, mutaction)

        //  update - only toOne
        val (nestedUpdates, nestedUpdateResults) = nestedUpdateDocsAndResults(node, mutaction)

        val combinedUpdates = combine(customCombine(scalarUpdates ++ nestedCreates ++ nestedDeletes ++ nestedUpdates): _*)
        val results         = nestedCreateResults ++ nestedDeleteResults ++ nestedUpdateResults :+ UpdateNodeResult(node.id, node, mutaction)

//        val arrayFilters = UpdateOptions().arrayFilters(List(equal("i.unique", 11)).asJava)

        collection
          .updateOne(mutaction.where, combinedUpdates) //, arrayFilters)
          .toFuture()
          .map(_ => MutactionResults(results))
    }
  }

  //endregion

  //region    Nested for nonEmbedded relations, not implemented yet

  def createNestedNode(mutaction: NestedCreateNode, parentId: IdGCValue)(implicit ec: ExecutionContext): SimpleMongoAction[MutactionResults] =
    SimpleMongoAction { database =>
      ???
    }

  def nestedDeleteNode(mutaction: NestedDeleteNode, parentId: IdGCValue)(implicit ec: ExecutionContext): SimpleMongoAction[MutactionResults] =
    //this should not trigger at the moment since embedded types should be deleted within their update
    SimpleMongoAction { database =>
      mutaction.where match {
        case None => sys.error("Only toMany deletes should arrive here.")
        case Some(nodeSelector) =>
          val parentModel                           = mutaction.relationField.model
          val collection: MongoCollection[Document] = database.getCollection(parentModel.name)

          val filter   = equal("_id", parentId.value)
          val pullPart = pull(mutaction.relationField.name, nodeSelector)

          collection
            .updateOne(filter, pullPart)
            .toFuture()
            .map(_ => MutactionResults(Vector.empty))

      }
    }

  def nestedUpdateNode(mutaction: NestedUpdateNode, parentId: IdGCValue)(implicit ec: ExecutionContext): SimpleMongoAction[MutactionResults] =
    SimpleMongoAction { database =>
      // this should not be called for embedded types since it should be rolled into the TopLevelUpdate

      //          db.coll.update({}, {$set: {“a.$[i].b”: 2}}, {arrayFilters: [{“i.b”: 0}]})
      //          Input: {a: [{b: 0}, {b: 1}]}
      //          Output: {a: [{b: 2}, {b: 1}]}

      //          val filter          = Filters.eq("_id", parentId.value)
      //          val setPart         = set("middle.$[i].fieldname", 12)
      //          val arrayFilterPart = UpdateOptions().arrayFilters(List(Filters.eq("i.unique", 11)).asJava)
      //
      //          collection
      //            .updateOne(filter, setPart, arrayFilterPart)
      //            .toFuture()
      //            .map(_ => MutactionResults(Vector.empty))

      ???
    }

  //endregion

  //TODO
  //Combine errorhandling and production of actions/results again

  private def createToDoc(mutaction: CreateNode,
                          results: Vector[DatabaseMutactionResult] = Vector.empty): (IdGCValue, Document, Vector[DatabaseMutactionResult]) = {
    val id = CuidGCValue.random

    val nonListValues: List[(String, GCValue)] =
      mutaction.model.scalarNonListFields
        .filter(field => mutaction.nonListArgs.hasArgFor(field) && mutaction.nonListArgs.getFieldValue(field.name).get != NullGCValue)
        .map(field => field.name -> mutaction.nonListArgs.getFieldValue(field).get)

    val nonListArgsWithId = nonListValues :+ ("_id", id)

    val (childResults: Vector[DatabaseMutactionResult], nestedCreateFields: Map[String, BsonValue]) = nestedCreateDocsAndResults(mutaction.nestedCreates)

    val thisResult = CreateNodeResult(id, mutaction)

    val doc = Document(nonListArgsWithId ++ mutaction.listArgs) ++ nestedCreateFields

    (id, doc, childResults :+ thisResult)
  }

  private def nestedCreateDocsAndResults(mutactions: Vector[NestedCreateNode]): (Vector[DatabaseMutactionResult], Map[String, BsonValue]) = {
    val nestedCreates: immutable.Seq[(RelationField, (IdGCValue, Document, Vector[DatabaseMutactionResult]))] =
      mutactions.map(m => m.relationField -> createToDoc(m))

    val childResults: Vector[DatabaseMutactionResult] = nestedCreates.flatMap(x => x._2._3).toVector

    val grouped: Map[RelationField, immutable.Seq[Document]] = nestedCreates.groupBy(_._1).mapValues(_.map(_._2._2))

    val nestedCreateFields = grouped.foldLeft(Map.empty[String, BsonValue]) { (map, t) =>
      val rf: RelationField = t._1
      val documents         = t._2.map(_.toBsonDocument)

      if (rf.isList) map + (rf.name -> BsonArray(documents)) else map + (rf.name -> documents.head)
    }

    (childResults, nestedCreateFields)
  }

  private def embeddedNestedDeleteActionsAndResults(node: PrismaNode, mutaction: UpdateNode, path: String = ""): (Vector[Bson], Vector[DeleteNodeResult]) = {
    def fieldName(f: String) = mutaction match {
      case _: TopLevelUpdateNode => f
      case _: NestedUpdateNode   => newFieldName(mutaction, f, path)
    }
    val parentWhere = mutaction match {
      case top: TopLevelUpdateNode  => Some(top.where)
      case nested: NestedUpdateNode => nested.where
    }

    def manyError(delete: NestedDeleteNode, childWhere: NodeSelector) = error(delete, Some(childWhere))
    def error(delete: NestedDeleteNode, where: Option[NodeSelector] = None) =
      throw NodesNotConnectedError(delete.relationField.relation, delete.relationField.model, parentWhere, delete.model, where)

    val actionsAndResults = mutaction.nestedDeletes.map {

      case toOneDelete @ NestedDeleteNode(_, rf, None) =>
        if (node.data.map(rf.name) == NullGCValue) error(toOneDelete)

        (unset(fieldName(rf.name)), DeleteNodeResult(CuidGCValue.dummy, PrismaNode(CuidGCValue.dummy, node.data.map(rf.name).asRoot), toOneDelete))

      case toManyDelete @ NestedDeleteNode(_, rf, Some(where)) =>
        node.data.map(rf.name) match {
          case NullGCValue =>
            manyError(toManyDelete, where)

          case ListGCValue(values) =>
            val rootValues = values.map(_.asRoot)
            rootValues.find(root => root.map(where.fieldName) == where.fieldGCValue) match {
              case None =>
                manyError(toManyDelete, where)

              case Some(previous) =>
                (pull(fieldName(rf.name), whereToBson(where)), DeleteNodeResult(CuidGCValue.dummy, PrismaNode(CuidGCValue.dummy, previous), toManyDelete))
            }

          case _ => sys.error("should not happen")
        }
    }

    (actionsAndResults.map(_._1), actionsAndResults.map(_._2))
  }

  private def toOneScalarUpdateValues(mutaction: UpdateNode, path: String = "") = {
    val invalidUpdates = mutaction.nonListArgs.raw.asRoot.map.collect { case (k, v) if v == NullGCValue && mutaction.model.getFieldByName_!(k).isRequired => k }
    if (invalidUpdates.nonEmpty) throw FieldCannotBeNull(invalidUpdates.head)

    val nonListValues = mutaction.nonListArgs.raw.asRoot.map.map { case (f, v) => set(newFieldName(mutaction, f, path), GCValueBsonTransformer(v)) }.toVector
    val listValues    = mutaction.listArgs.map { case (f, v) => set(newFieldName(mutaction, f, path), GCValueBsonTransformer(v)) }

    nonListValues ++ listValues
  }

  private def nestedUpdateDocsAndResults(node: PrismaNode, mutaction: UpdateNode, path: String = ""): (Vector[Bson], Vector[DatabaseMutactionResult]) = {

    val first: Vector[(Vector[Bson], Vector[DatabaseMutactionResult])] = mutaction.nestedUpdates.collect {
      case toOneUpdate @ NestedUpdateNode(_, rf, None, _, _, _, _, _, _, _, _) =>
        val subNode = node.toOneChild(rf) match {
          case None             => throw NodesNotConnectedError(rf.relation, rf.model, None, rf.relatedModel_!, None)
          case Some(prismaNode) => prismaNode
        }

        val scalarUpdates = toOneScalarUpdateValues(toOneUpdate, path)

        // combine this into one function
        val (nestedCreateResults, nestedCreateFields) = nestedCreateDocsAndResults(toOneUpdate.nestedCreates)
        val nestedCreates                             = nestedCreateFields.map { case (f, v) => set(newFieldName(toOneUpdate, f, path), v) }

        val (nestedUpdates, nestedUpdateResults) = nestedUpdateDocsAndResults(subNode, toOneUpdate, combineTwo(path, rf.name))

        val (nestedDeletes, nestedDeleteResults) = embeddedNestedDeleteActionsAndResults(subNode, toOneUpdate, path)

        val thisResult = UpdateNodeResult(subNode.id, subNode, toOneUpdate)

        (scalarUpdates ++ nestedCreates ++ nestedDeletes ++ nestedUpdates, nestedCreateResults ++ nestedDeleteResults ++ nestedUpdateResults :+ thisResult)

      case toManyUpdate @ NestedUpdateNode(_, rf, Some(where), _, _, _, _, _, _, _, _) =>
        val subNode = node.toManyChild(rf, where) match {
          case None             => throw NodesNotConnectedError(rf.relation, rf.model, None, rf.relatedModel_!, Some(where))
          case Some(prismaNode) => prismaNode
        }

//        val scalarUpdates = toManyScalarUpdateValues(toManyUpdate, path)
        val scalarUpdates = Vector(set("middle.$[i].name", "MiddleNew"))
//

//        // combine this into one function
//        val (nestedCreateResults, nestedCreateFields) = nestedCreateDocsAndResults(toManyUpdate.nestedCreates)
//        val nestedCreates                             = nestedCreateFields.map { case (f, v) => set(newFieldName(toManyUpdate, f, path), v) }
//
//        val (nestedUpdates, nestedUpdateResults) = nestedUpdateDocsAndResults(subNode, toManyUpdate, combineTwo(path, rf.name))
//
        val (nestedDeletes, nestedDeleteResults) = embeddedNestedDeleteActionsAndResults(subNode, toManyUpdate, path)
//
        val thisResult = UpdateNodeResult(subNode.id, subNode, toManyUpdate)
//
//        (scalarUpdates ++ nestedCreates ++ nestedDeletes ++ nestedUpdates, nestedCreateResults ++ nestedDeleteResults ++ nestedUpdateResults :+ thisResult)
        (scalarUpdates ++ nestedDeletes, nestedDeleteResults :+ thisResult)
    }
    (first.flatMap(_._1), first.flatMap(_._2))
  }

  private def toManyScalarUpdateValues(mutaction: UpdateNode, path: String = "") = {
//    val nonListValues = mutaction.nonListArgs.raw.asRoot.map.map { case (f, v) => set(newFieldName(mutaction, f, path), GCValueBsonTransformer(v)) }.toVector
//    val listValues    = mutaction.listArgs.map { case (f, v) => set(newFieldName(mutaction, f, path), GCValueBsonTransformer(v)) }
//
//    nonListValues ++ listValues

  }

  // helpers

  private def customCombine(updates: Vector[conversions.Bson]): Vector[conversions.Bson] = {
    //Fixme needs to comine updates that the Mongo DB combine would swallow since they reference the same field and value
    //{ "$pull" : { "comments" : { "alias" : "alias1" } } }
    //{ "$pull" : { "comments" : { "alias" : "alias2" } } }
    //in this case the second one would overwrite the first one -.-

//    updates.map { update =>
//      val res: Bson = update
//
//      val doc = update.toBsonDocument(classOf[Document], MongoClientSettings.getDefaultCodecRegistry)
//
//    }
    updates
  }

  private def newFieldName(mutaction: UpdateNode, fieldName: String, path: String) = mutaction match {
    case _: TopLevelUpdateNode => fieldName
    case m: NestedUpdateNode   => combineThree(path, m.relationField.name, fieldName)
  }
}
