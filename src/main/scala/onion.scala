package edu.mit.cryptdb

import scala.collection.mutable.{ ArrayBuffer, HashMap, Seq => MSeq }

object Onions {
  final val PLAIN        = 0x1
  final val DET          = 0x1 << 1
  final val OPE          = 0x1 << 2
  final val HOM          = 0x1 << 3
  final val HOM_ROW_DESC = 0x1 << 4
  final val HOM_AGG      = 0x1 << 5
  final val SWP          = 0x1 << 6

  // not doing HOM for now (use HOM_ROW_DESC/HOM_AGG instead)
  final val ALL          = 0x7FFFFFFF & ~HOM

  // Convenience bitmasks
  final val Countable        = PLAIN | DET | OPE
  final val Comparable       = PLAIN | DET | OPE
  final val EqualComparable  = PLAIN | DET
  final val IEqualComparable = PLAIN | OPE
  final val Searchable       = PLAIN | SWP

  def isDecryptable(o: Int): Boolean = {
    assert(BitUtils.onlyOne(o))
    o match {
      case PLAIN | HOM_ROW_DESC => false
      case _                    => true
    }
  }

  def str(o: Int): String = {
    if (BitUtils.onlyOne(o)) {
      o match {
        case PLAIN        => "PLAIN"
        case DET          => "DET"
        case OPE          => "OPE"
        case HOM          => "HOM"
        case HOM_ROW_DESC => "HOM_ROW_DESC"
        case HOM_AGG      => "HOM_AGG"
        case SWP          => "SWP"
        case _            => "UNKNOWN(0x%x)".format(o)
      }
    } else toSeq(o).map(str).mkString("(", "|", ")")
  }

  def onionFromStr(s: String): Option[Int] =
    s match {
      case "DET" => Some(DET)
      case "OPE" => Some(OPE)
      case "SWP" => Some(SWP)
      case _     => None
    }

  def pickOne(o: Int): Int = {
    def t(m: Int) = (o & m) != 0
    if      (t(PLAIN))        PLAIN
    else if (t(DET))          DET
    else if (t(OPE))          OPE
    else if (t(HOM))          HOM
    else if (t(HOM_ROW_DESC)) HOM_ROW_DESC
    else if (t(HOM_AGG))      HOM_AGG
    else if (t(SWP))          SWP
    else throw new RuntimeException("could not pick one onion from: 0x%x".format(o))
  }

  def toSeq(o: Int): Seq[Int] = {
    val buf = new ArrayBuffer[Int]
    def t(m: Int) = (o & m) != 0
    if (t(PLAIN))        buf += PLAIN
    if (t(DET))          buf += DET
    if (t(OPE))          buf += OPE
    if (t(HOM))          buf += HOM
    if (t(HOM_ROW_DESC)) buf += HOM_ROW_DESC
    if (t(HOM_AGG))      buf += HOM_AGG
    if (t(SWP))          buf += SWP
    buf.toSeq
  }

  def isSingleRowEncOnion(o: Int): Boolean = {
    assert(BitUtils.onlyOne(o))
    o match {
      case DET | OPE | SWP => true
      case _               => false
    }
  }

  def completeSeqWithPreference(o: Int): Seq[Int] = {
    val s = toSeq(o)
    val s0 = s.toSet
    s ++ {
      toSeq(Onions.ALL)
       .flatMap(x => if (s0.contains(x)) Seq.empty else Seq(x)) }
  }
}

// binds a specific selection of an onion (including plain)
object OnionType {
  def buildIndividual(onion: Int): OnionType = {
    assert(BitUtils.onlyOne(onion))
    assert((onion & (Onions.HOM_AGG | Onions.HOM_ROW_DESC)) == 0)
    if (onion == Onions.PLAIN) PlainOnion else RegularOnion(onion)
  }
}

sealed abstract trait OnionType {
  def onion: Int
  @inline def isOneOf(mask: Int): Boolean = (onion & mask) != 0
  @inline def isPlain: Boolean = onion == Onions.PLAIN

  def toCPP: String = onion match {
    case Onions.PLAIN => "oNONE"
    case Onions.DET   => "oDET"
    case Onions.OPE   => "oOPE"
    case Onions.SWP   => "oSWP"
    case Onions.HOM | Onions.HOM_ROW_DESC | Onions.HOM_AGG => "oAGG"
  }

  // hack for now...
  // TODO: need to take into account if we need the JOIN variants...
  def seclevelToCPP(join: Boolean = false): String = onion match {
    case Onions.PLAIN => "SECLEVEL::PLAIN"
    case Onions.DET   => if (join) "SECLEVEL::DETJOIN" else "SECLEVEL::DET"
    case Onions.OPE   => if (join) "SECLEVEL::OPEJOIN" else "SECLEVEL::OPE"
    case Onions.SWP   => "SECLEVEL::SWP"
    case Onions.HOM | Onions.HOM_ROW_DESC | Onions.HOM_AGG => "SECLEVEL::SEMANTIC_AGG"
  }
}

case object PlainOnion extends OnionType {
  val onion = Onions.PLAIN
}

case class RegularOnion(onion: Int) extends OnionType {
  assert(BitUtils.onlyOne(onion))
  assert((onion & (Onions.PLAIN | Onions.HOM_AGG | Onions.HOM_ROW_DESC)) == 0)
}

case class HomGroupOnion(relation: String, group: Int) extends OnionType {
  val onion = Onions.HOM_AGG
}

case class HomRowDescOnion(relation: String) extends OnionType {
  val onion = Onions.HOM_ROW_DESC
}

object OnionSet {
  def mergeSeq(sets: Seq[OnionSet]): OnionSet = {
    sets.foldLeft(new OnionSet) {
      case (acc, elem) => acc.merge(elem)
    }
  }
}

class OnionSet {
  private val _gen = new NameGenerator("virtual_local")

  // string is enc name, int is Onions bitmask
  private val opts = new HashMap[(String, Either[String, SqlExpr]), (String, Int)]

  // relation -> sequence of groups
  private type HomGroup = ArrayBuffer[SqlExpr]
  private val packedHOMs = new HashMap[String, ArrayBuffer[HomGroup]]

  override def hashCode: Int = {
    opts.hashCode ^ packedHOMs.hashCode
  }

  override def equals(o: Any): Boolean = o match {
    case that: OnionSet =>
      if (this eq that) return true
      opts == that.opts && packedHOMs == that.packedHOMs
    case _ => false
  }

  private def mkKey(relation: String, expr: SqlExpr) = {
    ((relation, expr match {
      case FieldIdent(_, n, _, _) => Left(n)
      case _ => Right(expr.withoutContextT[SqlExpr])
    }))
  }

  def withoutGroups: OnionSet = {
    val ret = new OnionSet
    ret.opts ++= opts
    ret
  }

  def withGroups(groups: Map[String, Seq[Seq[SqlExpr]]]): OnionSet = {
    val ret = withoutGroups
    ret.packedHOMs ++= groups.map { case (k, v) =>
      (k, ArrayBuffer( v.map( ss => ArrayBuffer(ss:_*) ) : _* ))
    }
    ret
  }

  def withGlobalPrecompExprs(exprs: Map[String, Seq[SqlExpr]]): OnionSet = {
    // TODO: impl is not efficient
    val ret = new OnionSet
    ret.opts ++= opts.map {
      case ((r, Right(e)), (_, o)) =>
        val gidx = exprs(r).indexWhere(_ == e)
        if (gidx == -1) {
          println("failing expr: " + e)
        }
        assert(gidx != -1)
        ((r, Right(e)), ("virtual_global" + gidx, o))
      case e => e
    }
    ret.packedHOMs ++= packedHOMs
    ret
  }

  @inline
  def groupsForRelations: Seq[String] = packedHOMs.keys.toSeq

  @inline
  def getHomGroups: Map[String, Seq[Seq[SqlExpr]]] =
    packedHOMs.map { case (k, v) => (k, v.map(_.toSeq).toSeq) }.toMap


  // does not include homs
  def getPrecomputedExpressions: Map[String, Map[SqlExpr, Int]] = {
    val m = new HashMap[String, HashMap[SqlExpr, Int]]
    opts.foreach {
      case ((r, Right(e)), (_, o)) =>
        val m0 = m.getOrElseUpdate(r, new HashMap[SqlExpr, Int])
        m0.put(e, m0.getOrElse(e, 0) | o)
      case _ =>
    }
    m.map { case (k, v) => (k, v.toMap) }.toMap
  }

  // relation is global table name
  def add(relation: String, expr: SqlExpr, o: Int): Unit = {
    assert((o & Onions.PLAIN) == 0)
    assert(BitUtils.onlyOne(o))
    val key = mkKey(relation, expr)
    opts.get(key) match {
      case Some((v1, v2)) =>
        opts.put(key, (v1, v2 | o))
      case None =>
        opts.put(key, (expr match {
          case FieldIdent(_, name, _, _) => name
          case _ => _gen.uniqueId()
          }, o))
    }
  }

  // adds to previous existing group. if no groups exist, adds to last
  def addPackedHOMToLastGroup(relation: String, expr: SqlExpr): Unit = {
    val expr0 = expr.withoutContextT[SqlExpr]
    packedHOMs.get(relation) match {
      case Some(groups) =>
        assert(!groups.isEmpty)
        val f = groups.last.toSet
        if (!f.contains(expr0)) groups.last += expr0
      case None =>
        packedHOMs.put(relation, ArrayBuffer(ArrayBuffer(expr0)))
    }
  }

  // return value is:
  // (group number (unique per relation), in group position (unique per group))
  def lookupPackedHOM(relation: String, expr: SqlExpr): Seq[(Int, Int)] = {
    val expr0 = expr.withoutContextT[SqlExpr]
    packedHOMs.get(relation).map { _.zipWithIndex.flatMap { case (group, gidx) =>
      group.zipWithIndex.filter { _._1 == expr0 }.map { case (_, pidx) => (gidx, pidx) }
    }}.getOrElse(Seq.empty)
  }

  def lookupPackedHOMById(relation: String, groupId: Int): Option[Seq[SqlExpr]] = {
    packedHOMs.get(relation).map(_.apply(groupId).toSeq)
  }

  // relation is global table name
  def lookup(relation: String, expr: SqlExpr): Option[(String, Int)] = {
    val ret = opts.get(mkKey(relation, expr))
    //println("lookup(%s, %s) = (%s)".format(relation, expr.sql, ret.toString))
    ret
  }

  def lookupPrecomputedByName(relation: String, name: String): Option[(SqlExpr, Int)] = {
    // impl is not efficient- we should build a by-name index
    opts.find {
      case ((r, Right(_)), (n, _)) if r == relation && n == name => true
      case _ => false
    }.map { case ((_, Right(e)), (_, o)) => (e, o) }
  }

  def merge(that: OnionSet): OnionSet = {
    val merged = new OnionSet
    merged.opts ++= opts
    that.opts.foreach {
      case (k, v @ (v1, v2)) =>
        merged.opts.get(k) match {
          case Some((ov1, ov2)) => merged.opts.put(k, (ov1, ov2 | v2))
          case None => merged.opts.put(k, v)
        }
    }
    merged.packedHOMs ++= packedHOMs
    that.packedHOMs.foreach {
      case (k, v) =>
        merged.packedHOMs.get(k) match {
          case Some(v0) => merged.packedHOMs.put(k, v ++ v0)
          case None => merged.packedHOMs.put(k, v)
        }
    }
    merged
  }

  def complete(d: Definitions): OnionSet = {
    val m = new OnionSet
    m.opts ++= opts
    m.packedHOMs ++= packedHOMs
    d.defns.foreach {
      case (relation, columns) =>
        columns.foreach(tc => {
          val key = (relation, Left(tc.name))
          m.opts.get(key) match {
            case Some((v1, v2)) =>
              if ((v2 & Onions.DET) == 0 &&
                  (v2 & Onions.OPE) == 0) {
                m.opts.put(key, (v1, v2 | Onions.DET))
              }
            case None => m.opts.put(key, (tc.name, Onions.DET))
          }
        })
    }
    m
  }

  // deep copy
  def copy: OnionSet = {
    val cpy = new OnionSet
    opts.foreach {
      case (k @ (relation, Left(name)), v) =>
        cpy.opts.put(k, v)
      case ((relation, Right(expr)), v) =>
        cpy.opts.put((relation, Right(expr.withoutContextT[SqlExpr])), v)
    }
    packedHOMs.foreach {
      case (k, vs) =>
        cpy.packedHOMs.put(k, vs.map(_.map(_.withoutContextT[SqlExpr])))
    }
    cpy
  }

  def isEmpty: Boolean = opts.isEmpty && packedHOMs.isEmpty

  def compactToString: String = {
    val o = opts.flatMap { case (k @ (rname, expr), (s, v)) =>
      val x = v & (~Onions.DET)
      if (x != 0 || expr.isRight) {
        Some((k, (s, if (expr.isRight) v else x)))
      } else {
        None
      }
    }
    "OnionSet(compact_opts = " + o.toString +
    ", packedHOMs = " + packedHOMs.toString + ")"
  }

  override def toString =
    "OnionSet(opts = " + opts.toString +
    ", packedHOMs = " + packedHOMs.toString + ")"

  def pretty: String = {
    val allTables = (opts.keys.map(_._1).toSet ++ packedHOMs.keys.toSet).toSeq.sorted

    // XXX: inefficient
    def optsByReln(reln: String): (Seq[(String, Int)], Seq[(SqlExpr, String, Int)])= {

      val left  = new ArrayBuffer[(String, Int)]
      val right = new ArrayBuffer[(SqlExpr, String, Int)]

      opts.foreach {
        case ((reln0, x), (n, o)) if reln == reln0 =>
          x match {
            case Left(col)   => left  += ((col, o))
            case Right(expr) => right += ((expr, n, o))
          }
        case _ =>
      }

      (left.toSeq, right.toSeq)
    }

    allTables.map { reln =>
      val s = new StringBuffer

      val (cols, precomp) = optsByReln(reln)

      s.append("%s:\n".format(reln))
      s.append("  columns:\n")
      cols.foreach { case (name, o) =>
        s.append("    %s: %s\n".format(name, Onions.str(o)))
      }
      s.append("  precomputed columns:\n")
      precomp.foreach { case (expr, name, o) =>
        s.append("    %s: %s %s\n".format(name, expr.sql, Onions.str(o)))
      }
      s.append("  hom groups:\n")
      packedHOMs.get(reln).foreach { groups =>
        s.append("    %s\n".format(groups.map(_.map(_.sql).mkString("{", ", ", "}"))))
      }

      s.toString
    }.mkString("", "\n\n", "")
  }
}

// this is a necessary evil until we rework the transformers api
// not thread safe
class SetOnce[T] {
  private var _value: Option[T] = None
  def set(v: T): Unit = {
    if (!_value.isDefined) _value = Some(v)
  }
  def get: Option[T] = _value
}
