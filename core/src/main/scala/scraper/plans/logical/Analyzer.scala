package scraper.plans.logical

import scraper.Catalog
import scraper.exceptions.{AnalysisException, IllegalAggregationException, ResolutionFailureException}
import scraper.expressions.NamedExpression.{AnonymousColumnName, UnquotedName, newExpressionID}
import scraper.expressions.ResolvedAttribute.intersectByID
import scraper.expressions._
import scraper.plans.logical.dsl._
import scraper.plans.logical.patterns._
import scraper.trees.RulesExecutor.{FixedPoint, Once}
import scraper.trees.{Rule, RulesExecutor}
import scraper.types.StringType

class Analyzer(catalog: Catalog) extends RulesExecutor[LogicalPlan] {
  private val resolutionBatch =
    RuleBatch("Resolution", FixedPoint.Unlimited, Seq(
      new ResolveRelations(catalog),
      ExpandStars,
      ResolveReferences,
      DeduplicateReferences,
      ResolveAliases,
      new ResolveFunctions(catalog),
      GlobalAggregates,
      ResolveAggregates
    ))

  private val typeCheckBatch =
    RuleBatch("Type check", Once, Seq(
      TypeCheck
    ))

  override def batches: Seq[RuleBatch] = Seq(
    resolutionBatch,
    typeCheckBatch
  )

  override def apply(tree: LogicalPlan): LogicalPlan = {
    logDebug(
      s"""Analyzing logical query plan:
         |
         |${tree.prettyTree}
         |""".stripMargin
    )
    super.apply(tree)
  }

  def resolve(tree: LogicalPlan): LogicalPlan = {
    logDebug(
      s"""Resolving logical query plan:
         |
         |${tree.prettyTree}
         |""".stripMargin
    )
    apply(tree, resolutionBatch :: Nil)
  }

  def typeCheck(tree: LogicalPlan): LogicalPlan = {
    if (tree.isResolved) {
      logDebug(
        s"""Type checking logical query plan:
           |
           |${tree.prettyTree}
           |""".stripMargin
      )
      apply(tree, typeCheckBatch :: Nil)
    } else {
      // Performs full analysis for unresolved logical query plan
      apply(tree)
    }
  }

  /**
   * This rule resolves unresolved relations by looking up the table name from the `catalog`.
   */
  class ResolveRelations(catalog: Catalog) extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformUp {
      case UnresolvedRelation(name) => catalog lookupRelation name
    }
  }

  /**
   * This rule expands "`*`" appearing in `SELECT`.
   */
  object ExpandStars extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformUp {
      case Unresolved(Resolved(child) Project projectList) =>
        child select (projectList flatMap {
          case Star => child.output
          case e    => Seq(e)
        })
    }
  }

  /**
   * This rule tries to resolve [[scraper.expressions.UnresolvedAttribute UnresolvedAttribute]]s in
   * an logical plan operator using output [[scraper.expressions.Attribute Attribute]]s of its
   * children.
   */
  @throws[ResolutionFailureException](
    "If no candidate or multiple ambiguous candidate input attributes can be found"
  )
  object ResolveReferences extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformUp {
      case Unresolved(plan) if plan.isDeduplicated =>
        resolveReferences(plan)
    }

    private def resolveReferences(plan: LogicalPlan): LogicalPlan = plan transformExpressionsUp {
      case UnresolvedAttribute(name, qualifier) =>
        def reportResolutionFailure(message: String): Nothing = {
          throw new ResolutionFailureException(
            s"""Failed to resolve attribute $name in logical query plan:
               |${plan.prettyTree}
               |$message
               |""".stripMargin
          )
        }

        // TODO Considers case insensitive name resolution
        val candidates = plan.children flatMap (_.output) filter {
          case a: AttributeRef => a.name == name && (qualifier.toSet subsetOf a.qualifier.toSet)
          case _               => false
        }

        candidates match {
          case Seq(attribute) =>
            attribute

          case Nil =>
            reportResolutionFailure("No candidate input attribute(s) found")

          case _ =>
            reportResolutionFailure {
              val list = candidates map (_.debugString) mkString ", "
              s"Multiple ambiguous input attributes found: $list"
            }
        }
    }
  }

  /**
   * This rule resolves ambiguous duplicated attributes/aliases introduced by binary logical query
   * plan operators like [[Join]] and [[SetOperator set operators]].  For example:
   * {{{
   *   // Self-join, equivalent to "SELECT * FROM t INNER JOIN t":
   *   val df = context table "t"
   *   val joined = df join df
   *
   *   // Self-union, equivalent to "SELECT 1 AS a UNION ALL SELECT 1 AS a":
   *   val df = context single (1 as 'a)
   *   val union = df union df
   * }}}
   */
  object DeduplicateReferences extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case plan if plan.isChildrenResolved && !plan.isDeduplicated =>
        plan match {
          case node: Join      => node.copy(right = deduplicateRight(node.left, node.right))
          case node: Union     => node.copy(right = deduplicateRight(node.left, node.right))
          case node: Intersect => node.copy(right = deduplicateRight(node.left, node.right))
          case node: Except    => node.copy(right = deduplicateRight(node.left, node.right))
        }
    }

    def deduplicateRight(left: LogicalPlan, right: LogicalPlan): LogicalPlan = {
      val conflictingAttributes = intersectByID(left.outputSet, right.outputSet)

      def hasDuplicates(attributes: Set[Attribute]): Boolean =
        intersectByID(attributes, conflictingAttributes).nonEmpty

      right collectFirst {
        // Handles relations that introduce ambiguous attributes
        case plan: MultiInstanceRelation if hasDuplicates(plan.outputSet) =>
          plan -> plan.newInstance()

        // Handles projections that introduce ambiguous aliases
        case plan @ Project(_, projectList) if hasDuplicates(collectAliases(projectList)) =>
          plan -> plan.copy(projectList = projectList map {
            case a: Alias => a withID newExpressionID()
            case e        => e
          })
      } map {
        case (oldPlan, newPlan) =>
          val attributeRewrites = (oldPlan.output map (_.expressionID) zip newPlan.output).toMap

          right transformDown {
            case plan if plan == oldPlan => newPlan
          } transformAllExpressions {
            case a: Attribute => attributeRewrites getOrElse (a.expressionID, a)
          }
      } getOrElse right
    }

    private def collectAliases(projectList: Seq[NamedExpression]): Set[Attribute] =
      projectList.collect { case a: Alias => a.toAttribute }.toSet
  }

  object ResolveAliases extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformAllExpressions {
      case AutoAlias(Resolved(child: NamedExpression)) => child
      case AutoAlias(Resolved(child: Expression))      => applyAlias(child)
    }

    private def applyAlias(child: Expression): NamedExpression = {
      // Uses `UnquotedName` to eliminate back-ticks and double-quotes in generated alias names.
      def rewrite(e: Expression): Expression = e.transformDown {
        case a: AttributeRef                  => UnquotedName(a)
        case Literal(lit: String, StringType) => UnquotedName(lit)
      }
      child as (rewrite(child).sql getOrElse AnonymousColumnName)
    }
  }

  class ResolveFunctions(catalog: Catalog) extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformAllExpressions {
      case UnresolvedFunction(name, args) if args forall (_.isResolved) =>
        val fnInfo = catalog.functionRegistry.lookupFunction(name)
        fnInfo.builder(args)
    }
  }

  object GlobalAggregates extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      // Converts a `Project` with aggregate function(s) into an `UnresolvedAggregate` without any
      // grouping keys.
      case Resolved(child Project projectList) if containsAggregation(projectList) =>
        child groupBy Nil agg projectList
    }

    private def containsAggregation(expressions: Seq[Expression]): Boolean =
      expressions exists (_.collectFirst { case _: AggregateFunction => () }.nonEmpty)
  }

  object ResolveAggregates extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case (agg: UnresolvedAggregate) Filter havingCondition =>
        agg.copy(havingCondition = Some(havingCondition))

      case (agg: UnresolvedAggregate) Sort ordering =>
        agg.copy(ordering = ordering)

      case agg: UnresolvedAggregate if agg.havingCondition exists (!_.isResolved) =>
        agg

      case agg: UnresolvedAggregate if agg.ordering exists (!_.isResolved) =>
        agg

      // Resolves an `UnresolvedAggregate` into a `Project` over an `Aggregate`
      case agg @ UnresolvedAggregate(Resolved(child), keys, projectList, condition, ordering) =>
        // Aliases all grouping keys
        val keyAliases = keys map GroupingAlias.apply
        val rewriteKeys = keys.zip(keyAliases.map(_.toAttribute)).toMap

        // Aliases all found aggregate functions
        val aggs = collectAggregation(projectList)
        val aggAliases = aggs map AggregationAlias.apply
        val rewriteAggs = (aggs: Seq[Expression]).zip(aggAliases.map(_.toAttribute)).toMap

        def rewrite(expression: Expression) = expression transformDown {
          case e => rewriteKeys orElse rewriteAggs applyOrElse (e, identity[Expression])
        }

        // Replaces grouping keys and aggregate functions in projected fields, having condition, and
        // sort ordering expressions.
        val rewrittenProjectList = projectList map rewrite
        val rewrittenCondition = condition map rewrite
        val rewrittenOrdering = ordering map { order =>
          order.copy(child = rewrite(order.child))
        }

        // Reports invalid aggregation expressions if any.  Project list of an `UnresolvedAggregate`
        // should only consist of aggregation functions, grouping keys, literals, operators and
        // function calls.  After rewriting aggregation functions and grouping keys to
        // `AggregationAttribute`s and `GroupingAttribute`s, no `AttributeRef`s should exist in the
        // rewritten project list.
        rewrittenProjectList ++ rewrittenCondition ++ rewrittenOrdering filter {
          _.collectFirst { case _: AttributeRef => () }.nonEmpty
        } foreach {
          e => throw new IllegalAggregationException(e, keyAliases)
        }

        val resolvedAgg = Aggregate(child, keyAliases, aggAliases)
        val withHaving = rewrittenCondition map resolvedAgg.filter getOrElse resolvedAgg
        val withOrdering = if (rewrittenOrdering.isEmpty) withHaving
        else withHaving orderBy rewrittenOrdering

        withOrdering select rewrittenProjectList
    }

    private def collectAggregation(expressions: Seq[Expression]): Seq[AggregateFunction] =
      expressions.flatMap(_ collect { case a: AggregateFunction => a }).distinct
  }

  /**
   * This rule tries to transform all resolved logical plans operators (and expressions within them)
   * into strictly typed form.
   */
  @throws[AnalysisException]("If some resolved logical query plan operator doesn't type check")
  object TypeCheck extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformUp {
      case Resolved(plan) => plan.strictlyTyped.get
    }
  }
}
