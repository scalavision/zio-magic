package zio.magic.macros.utils

import zio.magic.macros.graph.{Graph, GraphError, LayerLike, Node}
import zio.prelude.Validation
import zio.{Chunk, NonEmptyChunk}

import scala.reflect.macros.blackbox

trait ExprGraphSupport { self: MacroUtils =>
  val c: blackbox.Context
  import c.universe._

  case class ExprGraph(graph: Graph[LayerExpr]) {
    def buildLayerFor(output: List[String]): LayerExpr =
      if (output.isEmpty) {
        reify { zio.ZLayer.succeed(()) }.asInstanceOf[LayerExpr]
      } else
        graph.buildComplete(output) match {
          case Validation.Failure(errors) =>
            c.abort(c.enclosingPosition, renderErrors(errors))
          case Validation.Success(value) =>
            value
        }

    private def renderErrors(errors: NonEmptyChunk[GraphError[LayerExpr]]): String = {
      val allErrors = sortErrors(errors)

      val errorMessage =
        allErrors
          .map(renderError)
          .mkString("\n")
          .linesIterator
          .mkString("\n🪄  ")
      val magicTitle = fansi.Color.Red("ZLayer Magic Error").overlay(fansi.Underlined.On).toString()
      s"""
🪄  $magicTitle
🪄  $errorMessage

"""
    }

    /** Return only the first level of circular dependencies, as these will be the most relevant.
      */
    private def sortErrors(errors: NonEmptyChunk[GraphError[LayerExpr]]): Chunk[GraphError[LayerExpr]] = {
      val (circularDependencyErrors, otherErrors) = errors.distinct
        .partitionMap {
          case circularDependency: GraphError.CircularDependency[LayerExpr] =>
            Left(circularDependency)
          case other => Right(other)
        }
      val sorted                    = circularDependencyErrors.sortBy(_.depth)
      val lowestDepthCircularErrors = sorted.takeWhile(_.depth == sorted.headOption.map(_.depth).getOrElse(0))
      lowestDepthCircularErrors ++ otherErrors
    }

    private def renderError(error: GraphError[LayerExpr]): String =
      error match {
        case GraphError.MissingDependency(node, dependency) =>
          val styledDependency = fansi.Color.White(dependency).overlay(fansi.Underlined.On)
          val styledLayer      = fansi.Color.White(node.value.showTree)
          s"""
provide $styledDependency
    for $styledLayer"""

        case GraphError.MissingTopLevelDependency(dependency) =>
          val styledDependency = fansi.Color.White(dependency).overlay(fansi.Underlined.On)
          s"""missing $styledDependency"""

        case GraphError.CircularDependency(node, dependency, _) =>
          val styledNode       = fansi.Color.White(node.value.showTree).overlay(fansi.Underlined.On)
          val styledDependency = fansi.Color.White(dependency.value.showTree)
          s"""
${fansi.Color.Magenta("PARADOX ENCOUNTERED")} — Please don't open a rift in space-time!
$styledNode
both requires ${fansi.Bold.On("and")} is transitively required by $styledDependency
    """
      }

  }

  object ExprGraph {
    def apply(layers: List[Node[LayerExpr]]): ExprGraph =
      ExprGraph(Graph(layers))

    def buildLayer[R: c.WeakTypeTag](layers: List[Node[LayerExpr]]): LayerExpr =
      ExprGraph(Graph(layers)).buildLayerFor(getRequirements[R])
  }

  implicit val exprLayerLike: LayerLike[LayerExpr] =
    new LayerLike[LayerExpr] {
      import c.universe._

      override def composeH(lhs: LayerExpr, rhs: LayerExpr): LayerExpr =
        c.Expr(q"""$lhs ++ $rhs""")

      override def composeV(lhs: LayerExpr, rhs: LayerExpr): LayerExpr =
        c.Expr(q"""$lhs >>> $rhs""")
    }
}
