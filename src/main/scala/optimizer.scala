package edu.mit.cryptdb

trait RuntimeOptimizer {

  type CandidatePlans = Seq[(PlanNode, EstimateContext)]

  val config: OptimizerConfiguration

  // takes a physical design + stats + a query plan, and greedily computes
  // the best plan and returns possible plans

  def optimize(design: OnionSet, stats: Statistics, plan: SelectStmt):
    (PlanNode, EstimateContext, Estimate) = {
    val plans = generateCandidatePlans(design, plan)
    assert(!plans.isEmpty)
    val costs = plans.map { case (p, ctx) => (p, ctx, p.costEstimate(ctx, stats)) }
    costs.minBy(_._3.cost)
  }

  private object _gen extends Generator {
    val config = RuntimeOptimizer.this.config
  }

  def generateCandidatePlans(design: OnionSet, plan: SelectStmt): CandidatePlans = {
    val os = _gen.generateOnionSets(plan, Some(design))
    assert(!os.isEmpty)
    //println("physical design:")
    //println(design.compactToString)
    //println()
    //println("permuted subset:")
    //os.foreach { o =>
    //  println(o)
    //  println()
    //}
    _gen.generateCandidatePlans(os, plan)
  }
}
