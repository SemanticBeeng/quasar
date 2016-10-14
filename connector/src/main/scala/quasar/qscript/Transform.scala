/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.qscript

import quasar._
import quasar.contrib.matryoshka._
import quasar.ejson.{Int => _, _}
import quasar.fp._
import quasar.qscript.MapFunc._
import quasar.qscript.MapFuncs._
import quasar.Planner._
import quasar.Predef.{ Eq => _, _ }
import quasar.std.StdLib._

import matryoshka._, Recursive.ops._, FunctorT.ops._, TraverseT.nonInheritedOps._
import matryoshka.patterns._
import scalaz.{:+: => _, Divide => _, _}, Scalaz.{ToIdOps => _, _}, Inject._, Leibniz._
import shapeless.{nat, Sized}

// Need to keep track of our non-type-ensured guarantees:
// - all conditions in a ThetaJoin will refer to both sides of the join
// - the common source in a Join or Union will be the longest common branch
// - any unreferenced src will be `Unreferenced`, and no `Unreferenced` will
//   ever be referenced
// - ReduceIndices will not exceed the reducer bounds, and every reducer will be
//   referenced at least once.

// TODO: Could maybe require only Functor[F], once CoEnv exposes the proper
//       instances
class Transform
  [T[_[_]]: Recursive: Corecursive: FunctorT: EqualT: ShowT,
    F[_]: Traverse: Normalizable]
  (implicit
    C:  Coalesce.Aux[T, F, F],
    DE: Const[DeadEnd, ?] :<: F,
    QC: QScriptCore[T, ?] :<: F,
    TJ: ThetaJoin[T, ?] :<: F,
    PB: ProjectBucket[T, ?] :<: F,
    // TODO: Remove this one once we have multi-sorted AST
    FI: Injectable.Aux[F, QScriptTotal[T, ?]],
    mergeable:  Mergeable.Aux[T, F],
    eq:         Delay[Equal, F],
    show:       Delay[Show, F]) extends TTypes[T] {

  private val prov = new Provenance[T]
  private val rewrite = new Rewrite[T]

  private type LinearF = List[F[ExternallyManaged]]

  case class ZipperSides(
    lSide: FreeMap,
    rSide: FreeMap)

  case class ZipperTails(
    lTail: LinearF,
    rTail: LinearF)

  case class ZipperAcc(
    acc: LinearF,
    sides: ZipperSides,
    tails: ZipperTails)

  // TODO: Convert to NEL
  def linearize[F[_]: Functor: Foldable]:
      Algebra[F, List[F[ExternallyManaged]]] =
    fl => fl.as[ExternallyManaged](Extern) :: fl.fold

  private def delinearizeInner[A]: Coalgebra[F, List[F[A]]] = {
    case Nil    => DE.inj(Const[DeadEnd, List[F[A]]](Root))
    case h :: t => h.as(t)
  }

  private def delinearizeTargets[F[_]: Functor, A]:
      ElgotCoalgebra[Hole \/ ?, F, List[F[A]]] = {
    case Nil    => SrcHole.left[F[List[F[A]]]]
    case h :: t => h.as(t).right
  }

  private val consZipped: Algebra[ListF[F[ExternallyManaged], ?], ZipperAcc] = {
    case NilF() => ZipperAcc(Nil, ZipperSides(HoleF[T], HoleF[T]), ZipperTails(Nil, Nil))
    case ConsF(head, ZipperAcc(acc, sides, tails)) => ZipperAcc(head :: acc, sides, tails)
  }

  private val zipper:
      ElgotCoalgebra[
        ZipperAcc \/ ?,
        ListF[F[ExternallyManaged], ?],
        (ZipperSides, ZipperTails)] = {
    case (zs @ ZipperSides(lm, rm), zt @ ZipperTails(l :: ls, r :: rs)) =>
      mergeable.mergeSrcs(lm, rm, l, r).fold[ZipperAcc \/ ListF[F[ExternallyManaged], (ZipperSides, ZipperTails)]](
        ZipperAcc(Nil, zs, zt).left) {
          case SrcMerge(inn, lmf, rmf) =>
            ConsF(inn, (ZipperSides(lmf, rmf), ZipperTails(ls, rs))).right[ZipperAcc]
      }
    case (sides, tails) =>
      ZipperAcc(Nil, sides, tails).left
  }

  /** Contains a common src, the MapFuncs required to access the left and right
    * sides, and the FreeQS that were unmergeable on either side.
    */
  private case class MergeResult(src: T[F], lval: FreeQS, rval: FreeQS)

  private def merge(left: T[F], right: T[F]): MergeResult = {
    val lLin = left.cata(linearize).reverse
    val rLin = right.cata(linearize).reverse

    val ZipperAcc(common, ZipperSides(lMap, rMap), ZipperTails(lTail, rTail)) =
      elgot(
        (ZipperSides(HoleF[T], HoleF[T]), ZipperTails(lLin, rLin)))(
        consZipped, zipper)

    val leftF =
      foldIso(CoEnv.freeIso[Hole, F])
        .get(lTail.reverse.ana[T, CoEnv[Hole, F, ?]](delinearizeTargets[F, ExternallyManaged] >>> (CoEnv(_)))).mapSuspension(FI.inject)

    val rightF =
      foldIso(CoEnv.freeIso[Hole, F])
        .get(rTail.reverse.ana[T, CoEnv[Hole, F, ?]](delinearizeTargets[F, ExternallyManaged] >>> (CoEnv(_)))).mapSuspension(FI.inject)

    MergeResult(common.reverse.ana[T, F](delinearizeInner),
      rebaseBranch(leftF, lMap),
      rebaseBranch(rightF, rMap))
  }

  private case class AutoJoinBase(src: T[F], buckets: List[FreeMap])
  private case class AutoJoinResult(base: AutoJoinBase, lval: FreeMap, rval: FreeMap)
  private case class AutoJoin3Result(base: AutoJoinBase, lval: FreeMap, cval: FreeMap, rval: FreeMap)

  /** This unifies a pair of sources into a single one, with additional
    * expressions to access the combined bucketing info, as well as the left and
    * right values.
    */
  private def autojoin(left: Target[F], right: Target[F])
      : AutoJoinResult = {
    val lann = left.ann
    val rann = right.ann
    val MergeResult(src, lBranch, rBranch) = merge(left.value, right.value)

    val lprovs = concatBuckets(lann.provenance) ∘ (_.leftMap(_.as[JoinSide](LeftSide)))
    val rprovs = concatBuckets(rann.provenance) ∘ (_.leftMap(_.as[JoinSide](RightSide)))
    val (combine, newProvs, lacc, racc) =
      (lprovs, rprovs) match {
        case (None, None) =>
          val (combine, lacc, racc) =
            concat(
              lann.values.as[JoinSide](LeftSide),
              rann.values.as[JoinSide](RightSide))
          (combine, Nil, lacc, racc)
        case (None, Some((rBuck, rProvs))) =>
          val (combine, bacc, lacc, racc) =
            concat3(
              rBuck,
              lann.values.as[JoinSide](LeftSide),
              rann.values.as[JoinSide](RightSide))
          (combine, prov.joinProvenances(Nil, rProvs.map(_ >> bacc).toList), lacc, racc)
        case (Some((lBuck, lProvs)), None) =>
          val (combine, bacc, lacc, racc) =
            concat3(
              lBuck,
              lann.values.as[JoinSide](LeftSide),
              rann.values.as[JoinSide](RightSide))
          (combine, prov.joinProvenances(lProvs.map(_ >> bacc).toList, Nil), lacc, racc)
        case (Some((lBuck, lProvs)), Some((rBuck, rProvs))) =>
          val (combine, lbacc, rbacc, lacc, racc) =
            concat4(
              lBuck,
              rBuck,
              lann.values.as[JoinSide](LeftSide),
              rann.values.as[JoinSide](RightSide))
          (combine, prov.joinProvenances(lProvs.map(_ >> lbacc).toList, rProvs.map(_ >> rbacc).toList), lacc, racc)
      }

    AutoJoinResult(
      AutoJoinBase(
        rewrite.unifySimpleBranches[F, T[F]](src, lBranch, rBranch, combine)(rewrite.rebaseT).getOrElse {
          // FIXME: Need a better prov representation, to know when the provs are
          //        the same even when the paths to the values differ.
          val commonProv =
            lann.provenance.reverse.zip(rann.provenance.reverse).reverse.foldRightM[List[FreeMap] \/ ?, List[FreeMap]](Nil) {
              case ((l, r), acc) => if (l ≟ r) (l :: acc).right else acc.left
            }.merge

          val commonBuck = concatBuckets(commonProv)

          val condition: JoinFunc = commonBuck.fold(
            BoolLit[T, JoinSide](true))( // when both sides are empty, perform a full cross
            c => Free.roll[MapFunc, JoinSide](Eq(
              c._1.as(LeftSide),
              c._1.as(RightSide))))

          TJ.inj(ThetaJoin(src, lBranch, rBranch, condition, Inner, combine))
        }.embed,
        newProvs),
      lacc,
      racc)
  }

  /** A convenience for a pair of autojoins, does the same thing, but returns
    * access to all three values.
    */
  private def autojoin3(left: Target[F], center: Target[F], right: Target[F]):
      AutoJoin3Result = {
    val AutoJoinResult(AutoJoinBase(lsrc, lbuckets), lval, cval) =
      autojoin(left, center)
    val AutoJoinResult(base, bval, rval) =
      autojoin(Target(Ann(lbuckets, HoleF), lsrc), right)

    // the holes in `bval` reference `fullSrc`
    // so we replace the holes in `lval` with `bval` because the holes in `lval >> bval` must reference `fullSrc`
    // and `bval` applied to `fullSrc` gives us access to `lsrc`, so we apply `lval` after `bval`
    AutoJoin3Result(base, lval >> bval, cval >> bval, rval)
  }

  private def merge3Map(
    values: Func.Input[Target[F], nat._3])(
    func: (FreeMap, FreeMap, FreeMap) => MapFunc[FreeMap]):
      Target[F] = {
    val AutoJoin3Result(base, lval, cval, rval) =
      autojoin3(values(0), values(1), values(2))
    Target(Ann(base.buckets, Free.roll(func(lval, cval, rval))), base.src)
  }

  private def shiftValues(input: Target[F], f: FreeMap => MapFunc[FreeMap]):
      Target[F] = {
    val Ann(provs, value) = input.ann
    val (sides, leftAccess, rightAccess) =
      concat(
        Free.point[MapFunc, JoinSide](LeftSide),
        Free.point[MapFunc, JoinSide](RightSide))

    Target(Ann(
      prov.shiftMap(Free.roll[MapFunc, Hole](ProjectIndex(rightAccess, IntLit(0)))) :: provs.map(_ >> leftAccess),
      Free.roll(ProjectIndex(rightAccess, IntLit(1)))),
      QC.inj(LeftShift(input.value, Free.roll(f(value)), sides)).embed)
  }

  private def shiftIds(input: Target[F], f: FreeMap => MapFunc[FreeMap]):
      Target[F] = {
    val Ann(provs, value) = input.ann
    val (sides, leftAccess, rightAccess) =
      concat(
        Free.point[MapFunc, JoinSide](LeftSide),
        Free.point[MapFunc, JoinSide](RightSide))

    Target(Ann(
      prov.shiftMap(rightAccess) :: provs.map(_ >> leftAccess),
      rightAccess),
      QC.inj(LeftShift(input.value, Free.roll(f(value)), sides)).embed)
  }

  private def flatten(input: Target[F]): Target[F] = {
    val Target(Ann(buckets, value), fa) = input
    Target(Ann(prov.nestProvenances(buckets), value), fa)
  }

  // NB: More complicated LeftShifts are generated as an optimization:
  // before: ThetaJoin(cs, Map(Hole, mf), LeftShift(Hole, struct, repair), comb)
  // after: LeftShift(cs, struct, comb.flatMap(LeftSide => mf.map(_ => LeftSide), RS => repair))
  private def invokeExpansion1(
    func: UnaryFunc,
    values: Func.Input[Target[F], nat._1]):
      Target[F] =
    func match {
      // id(p, x) - {foo: 12, bar: 18}
      // id(p, y) - {foo: 1, bar: 2}
      //   id(p, x:foo) - 12
      //   id(p, x:bar) - 18
      //   id(p, x:foo) - 1
      //   id(p, x:bar) - 2
      // (one bucket)
      case structural.FlattenMap =>
        flatten(shiftValues(values(0), ZipMapKeys(_)))
      case structural.FlattenArray =>
        flatten(shiftValues(values(0), ZipArrayIndices(_)))

      // id(p, x) - {foo: 12, bar: 18}
      // id(p, y) - {foo: 1, bar: 2}
      //   id(p, x:foo) - foo
      //   id(p, x:bar) - bar
      //   id(p, y:foo) - foo
      //   id(p, y:bar) - bar
      // (one bucket)
      case structural.FlattenMapKeys =>
        flatten(shiftIds(values(0), DupMapKeys(_)))
      case structural.FlattenArrayIndices =>
        flatten(shiftIds(values(0), DupArrayIndices(_)))

      // id(p, x) - {foo: 12, bar: 18}
      // id(p, y) - {foo: 1, bar: 2}
      //   id(p, x, foo) - 12
      //   id(p, x, bar) - 18
      //   id(p, y, foo) - 1
      //   id(p, y, bar) - 2
      // (two buckets)
      case structural.ShiftMap   => shiftValues(values(0), ZipMapKeys(_))
      case structural.ShiftArray => shiftValues(values(0), ZipArrayIndices(_))

      // id(p, x) - {foo: 12, bar: 18}
      // id(p, y) - {foo: 1, bar: 2}
      //   id(p, x, foo) - foo
      //   id(p, x, bar) - bar
      //   id(p, y, foo) - foo
      //   id(p, y, bar) - bar
      // (two buckets)
      case structural.ShiftMapKeys      => shiftIds(values(0), DupMapKeys(_))
      case structural.ShiftArrayIndices => shiftIds(values(0), DupArrayIndices(_))
    }

  private def invokeExpansion2(func: BinaryFunc, values: Func.Input[Target[F], nat._2]):
      Target[F] =
    func match {
      case set.Range =>
        val join: AutoJoinResult = autojoin(values(0), values(1))
        val (sides, leftAccess, rightAccess) =
          concat(
            Free.point[MapFunc, JoinSide](LeftSide),
            Free.point[MapFunc, JoinSide](RightSide))

        Target(Ann(
          NullLit[T, Hole]() :: join.base.buckets.map(_ >> leftAccess),
          rightAccess),
          QC.inj(LeftShift(
            join.base.src,
            Free.roll(Range(join.lval, join.rval)),
            sides)).embed)
    }

  private def invokeReduction1(
    func: UnaryFunc,
    values: Func.Input[Target[F], nat._1]):
      Target[F] = {
    val Ann(provs, reduce) = values(0).ann
    // NB: If there’s no provenance, then there’s nothing to reduce. We’re
    //     already holding a single value.
    provs.tailOption.fold(values(0)) { tail =>
      concatBuckets(tail) match {
        case Some((newProvs, provAccess)) =>
          Target(Ann(
            provAccess.list.toList.map(_ >> Free.roll(ProjectIndex(HoleF[T], IntLit[T, Hole](0)))),
            Free.roll(ProjectIndex(HoleF[T], IntLit[T, Hole](1)))),
            QC.inj(Reduce[T, T[F]](
              values(0).value,
              newProvs,
              List(
                ReduceFuncs.Arbitrary(newProvs),
                ReduceFunc.translateUnaryReduction[FreeMap](func)(reduce)),
              Free.roll(ConcatArrays(
                Free.roll(MakeArray(Free.point(ReduceIndex(0)))),
                Free.roll(MakeArray(Free.point(ReduceIndex(1)))))))).embed)
        case None =>
          Target(EmptyAnn[T],
            QC.inj(Reduce[T, T[F]](
              values(0).value,
              NullLit(),
              List(ReduceFunc.translateUnaryReduction[FreeMap](func)(reduce)),
              Free.point(ReduceIndex(0)))).embed)
      }
    }
  }

  private def invokeReduction2(func: BinaryFunc, values: Func.Input[Target[F], nat._2])
      : Target[F] = {
    val join: AutoJoinResult = autojoin(values(0), values(1))

    // NB: If there’s no provenance, then there’s nothing to reduce. We’re
    //     already holding a single value.
    join.base.buckets.tailOption.fold(Target(EmptyAnn[T], join.base.src)) { tail =>
      concatBuckets(tail) match {
        case Some((newProvs, provAccess)) =>
          Target(Ann(
            provAccess.list.toList.map(_ >> Free.roll(ProjectIndex(HoleF[T], IntLit[T, Hole](0)))),
            Free.roll(ProjectIndex(HoleF[T], IntLit[T, Hole](1)))),
            QC.inj(Reduce[T, T[F]](
              values(0).value,
              newProvs,
              List(
                ReduceFuncs.Arbitrary(newProvs),
                ReduceFunc.translateBinaryReduction[FreeMap](func)(join.lval, join.rval)),
              Free.roll(ConcatArrays(
                Free.roll(MakeArray(Free.point(ReduceIndex(0)))),
                Free.roll(MakeArray(Free.point(ReduceIndex(1)))))))).embed)
        case None =>
          Target(EmptyAnn[T],
            QC.inj(Reduce[T, T[F]](
              values(0).value,
              NullLit(),
              List(ReduceFunc.translateBinaryReduction[FreeMap](func)(join.lval, join.rval)),
              Free.point(ReduceIndex(0)))).embed)
      }
    }
  }

  private def invokeThetaJoin(values: Func.Input[Target[F], nat._3], tpe: JoinType)
      : PlannerError \/ Target[F] = {
    val condError: PlannerError \/ JoinFunc = {
      // FIXME: This won’t work where we join a collection against itself
      TJ.prj(QC.inj(reifyResult(values(2).ann, values(2).value)).embed.transCata(rewrite.normalize).project).fold(
        (InternalError(s"non theta join condition found: ${values(2).value.shows} with provenance: ${values(2).ann.shows}"): PlannerError).left[JoinFunc])(
        _.combine.right[PlannerError])
    }

    condError.map { cond =>
      val merged: MergeResult = merge(values(0).value, values(1).value)

      val Ann(leftBuckets, leftValue) = values(0).ann
      val Ann(rightBuckets, rightValue) = values(1).ann

      // NB: This is a magic structure. Improve LP to not imply this structure.
      val combine: JoinFunc = Free.roll(ConcatMaps(
        Free.roll(MakeMap(StrLit[T, JoinSide]("left"), leftValue.as(LeftSide))),
        Free.roll(MakeMap(StrLit[T, JoinSide]("right"), rightValue.as(RightSide)))))

      // FIXME: The provenances are not correct here
      Target(Ann(prov.joinProvenances(leftBuckets, rightBuckets), HoleF),
        TJ.inj(ThetaJoin(
          merged.src,
          merged.lval,
          merged.rval,
          cond,
          tpe,
          combine)).embed)
    }
  }

  private def ProjectTarget(prefix: Target[F], field: FreeMap): Target[F] = {
    val Ann(provenance, values) = prefix.ann
    Target(Ann(prov.projectField(field) :: provenance, values),
      PB.inj(BucketField(prefix.value, HoleF[T], field)).embed)
  }

  private def pathToProj(path: pathy.Path[_, _, _]): Target[F] =
    pathy.Path.peel(path).fold[Target[F]](
      Target(EmptyAnn[T], DE.inj(Const[DeadEnd, T[F]](Root)).embed)) {
      case (p, n) =>
        ProjectTarget(pathToProj(p), StrLit(n.fold(_.value, _.value)))
    }

  private def fromData[T[_[_]]: Corecursive](data: Data): Data \/ T[EJson] = {
    data.hyloM[Data \/ ?, CoEnv[Data, EJson, ?], T[EJson]](
      interpretM[Data \/ ?, EJson, Data, T[EJson]](
        _.left,
        _.embed.right),
      Data.toEJson[EJson].apply(_).right)
  }

  /** We carry around metadata with pointers to provenance and result value as
    * we build up QScript. At the point where a particular chain ends (E.g., the
    * final result, or the `count` of a Subset, we need to make sure we
    * extract the result pointed to by the metadata.
    */
  def reifyResult[A](ann: Ann, src: A): QScriptCore[A] =
    quasar.qscript.Map(src, ann.values)

  // TODO: Replace disjunction with validation.
  def lpToQScript: AlgebraM[PlannerError \/ ?, LogicalPlan, Target[F]] = {
    case LogicalPlan.ReadF(path) =>
      // TODO: Compilation of SQL² should insert a ShiftMap at each FROM,
      //       however doing that would break the old Mongo backend, and we can
      //       handle it here for now. But it should be moved to the SQL²
      //       compiler when the old Mongo backend is replaced. (#1298)
      shiftValues(pathToProj(path), ZipMapKeys(_)).right

    case LogicalPlan.ConstantF(data) =>
      fromData(data).fold[PlannerError \/ MapFunc[FreeMap]](
        {
          case Data.NA => Undefined[T, FreeMap]().right
          case d       => NonRepresentableData(d).left
        },
        Constant[T, FreeMap](_).right) ∘ (mf =>
        Target(
          Ann(Nil, Free.roll[MapFunc, Hole](mf)),
          QC.inj(Unreferenced[T, T[F]]()).embed))

    case LogicalPlan.FreeF(name) =>
      (Planner.UnboundVariable(name): PlannerError).left[Target[F]]

    case LogicalPlan.LetF(name, form, body) =>
      (Planner.InternalError("un-elided Let"): PlannerError).left[Target[F]]

    case LogicalPlan.TypecheckF(expr, typ, cont, fallback) =>
      merge3Map(Func.Input3(expr, cont, fallback))(Guard(_, typ, _, _)).right[PlannerError]

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1))
        if func.effect ≟ Mapping =>
      val Ann(buckets, value) = a1.ann
      Target(
        Ann(buckets, Free.roll[MapFunc, Hole](MapFunc.translateUnaryMapping(func)(value))),
        a1.value).right

    case LogicalPlan.InvokeFUnapply(structural.ObjectProject, Sized(a1, a2)) =>
      val AutoJoinResult(base, lval, rval) = autojoin(a1, a2)
      // FIXME: This is a workaround because ProjectBucket doesn’t currently
      //        propagate provenance. (#1573)
      Target(
        Ann[T](base.buckets, Free.roll(ProjectField(lval, rval))),
        base.src).right
      // (Ann[T](buckets, HoleF[T]),
      //   PB.inj(BucketField(src, lval, rval)).embed).right

    case LogicalPlan.InvokeFUnapply(structural.ArrayProject, Sized(a1, a2)) =>
      val AutoJoinResult(base, lval, rval) = autojoin(a1, a2)
      // FIXME: This is a workaround because ProjectBucket doesn’t currently
      //        propagate provenance. (#1573)
      Target(
        Ann[T](base.buckets, Free.roll(ProjectIndex(lval, rval))),
        base.src).right
      // (Ann[T](buckets, HoleF[T]),
      //   PB.inj(BucketIndex(src, lval, rval)).embed).right

    case LogicalPlan.InvokeFUnapply(func @ BinaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2))
        if func.effect ≟ Mapping =>
      val AutoJoinResult(base, lval, rval) = autojoin(a1, a2)
      Target(
        Ann[T](base.buckets, Free.roll(MapFunc.translateBinaryMapping(func)(lval, rval))),
        base.src).right[PlannerError]

    case LogicalPlan.InvokeFUnapply(func @ TernaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2, a3))
        if func.effect ≟ Mapping =>
      merge3Map(Func.Input3(a1, a2, a3))(MapFunc.translateTernaryMapping(func)).right[PlannerError]

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1))
        if func.effect ≟ Reduction =>
      invokeReduction1(func, Func.Input1(a1)).right

    case LogicalPlan.InvokeFUnapply(func @ BinaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2))
        if func.effect ≟ Reduction =>
      invokeReduction2(func, Func.Input2(a1, a2)).right

    case LogicalPlan.InvokeFUnapply(set.Distinct, Sized(a1)) =>
      invokeReduction1(
        agg.Arbitrary,
        Func.Input1(
          Target(
            Ann(
              prov.swapProvenances(a1.ann.values :: a1.ann.provenance),
              a1.ann.values),
            a1.value))).right

    case LogicalPlan.InvokeFUnapply(set.Take, Sized(a1, a2)) =>
      val merged: MergeResult = merge(a1.value, a2.value)

      Target(a1.ann, QC.inj(Subset(merged.src, merged.lval, Take, Free.roll(FI.inject(QC.inj(reifyResult(a2.ann, merged.rval)))))).embed).right

    case LogicalPlan.InvokeFUnapply(set.Drop, Sized(a1, a2)) =>
      val merged: MergeResult = merge(a1.value, a2.value)

      Target(a1.ann, QC.inj(Subset(merged.src, merged.lval, Drop, Free.roll(FI.inject(QC.inj(reifyResult(a2.ann, merged.rval)))))).embed).right

    case LogicalPlan.InvokeFUnapply(set.OrderBy, Sized(a1, a2, a3)) =>
      val AutoJoin3Result(base, dataset, keys, directions) = autojoin3(a1, a2, a3)

      val keysList: List[FreeMap] = keys.toCoEnv[T].project match {
        case StaticArray(as) => as.map(_.fromCoEnv)
        case mf              => List(mf.embed.fromCoEnv)
      }

      val directionsList: PlannerError \/ List[SortDir] = {
        val orderStrs: PlannerError \/ List[String] = {
	  QC.prj(a3.value.project) match {
	    case Some(Map(src, mf)) if QC.prj(src.project) ≟ Some(Unreferenced()) =>
	      mf.toCoEnv[T].project match {
                case StaticArray(as) => as.traverse(x => StrLit.unapply(x.project)) \/> InternalError("unsupported ordering type")
                case StrLit(str)     => List(str).right
                case _               => InternalError("unsupported ordering function").left
	      }
	    case None => InternalError("oops").left
	  }
	}
        orderStrs.flatMap {
          _.traverse {
            case "ASC"  => SortDir.Ascending.right
            case "DESC" => SortDir.Descending.right
            case _      => InternalError("unsupported ordering direction").left
          }
        }
      }

      directionsList.map(dirs =>
        concatBuckets(base.buckets).fold(
          Target(
            Ann[T](Nil, dataset),
            QC.inj(Sort(base.src, NullLit(), keysList.zip(dirs))).embed)) {
          case (newProvs, provAccess) =>
            Target(
              Ann[T](provAccess.list.toList, dataset),
              QC.inj(Sort(base.src, newProvs, keysList.zip(dirs))).embed)
        })

    case LogicalPlan.InvokeFUnapply(set.Filter, Sized(a1, a2)) =>
      val AutoJoinResult(base, lval, rval) = autojoin(a1, a2)
      Target(
        Ann[T](base.buckets, lval),
        QC.inj(Filter(base.src, rval)).embed).right

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1))
        if func.effect ≟ Squashing =>
      val Ann(buckets, value) = a1.ann
      Target(Ann(prov.squashProvenances(buckets), value), a1.value).right

    case LogicalPlan.InvokeFUnapply(func @ UnaryFunc(_, _, _, _, _, _, _, _), Sized(a1))
        if func.effect ≟ Expansion =>
      invokeExpansion1(func, Func.Input1(a1)).right

    case LogicalPlan.InvokeFUnapply(func @ BinaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2))
        if func.effect ≟ Expansion =>
      invokeExpansion2(func, Func.Input2(a1, a2)).right

    case LogicalPlan.InvokeFUnapply(set.GroupBy, Sized(a1, a2)) =>
      val join: AutoJoinResult = autojoin(a1, a2)
      Target(Ann(prov.swapProvenances(join.rval :: join.base.buckets), join.lval), join.base.src).right

    case LogicalPlan.InvokeFUnapply(set.Union, Sized(a1, a2)) =>
      val MergeResult(src, lfree, rfree) = merge(a1.value, a2.value)
      val lbranch = Free.roll(FI.inject(QC.inj(reifyResult(a1.ann, lfree))))
      val rbranch = Free.roll(FI.inject(QC.inj(reifyResult(a2.ann, rfree))))

      // TODO: Need to align provenances, so each component is at the same
      //       location on both sides
      (concatBuckets(a1.ann.provenance), concatBuckets(a2.ann.provenance)) match {
        case (None, None) =>
          Target(Ann[T](Nil, HoleF), QC.inj(Union(src, lbranch, rbranch)).embed).right
        case (None, Some((rBuck, rProvs))) =>
          val (merged, rbacc, vacc) = concat(rBuck, HoleF[T])
          Target(
            Ann(rProvs.map(_ >> rbacc).toList, vacc),
            QC.inj(Union(src,
              Free.roll(FI.inject(QC.inj(Map(lbranch, merged)))),
              Free.roll(FI.inject(QC.inj(Map(rbranch, merged)))))).embed).right
        case (Some((lBuck, lProvs)), None) =>
          val (merged, lbacc, vacc) = concat(lBuck, HoleF[T])
          Target(
            Ann(lProvs.map(_ >> lbacc).toList, vacc),
            QC.inj(Union(src,
              Free.roll(FI.inject(QC.inj(Map(lbranch, merged)))),
              Free.roll(FI.inject(QC.inj(Map(rbranch, merged)))))).embed).right
        case (Some((lBuck, lProvs)), Some((rBuck, rProvs))) =>
          val (merged, lbacc, lvacc) = concat(lBuck, HoleF[T])
          val (_, rbacc, rvacc) = concat(rBuck, HoleF[T])
          (lbacc ≟ rbacc && lvacc ≟ rvacc).fold(
            Target(
              Ann(lProvs.map(_ >> lbacc).toList, lvacc),
              QC.inj(Union(src,
                Free.roll(FI.inject(QC.inj(Map(lbranch, merged)))),
                Free.roll(FI.inject(QC.inj(Map(rbranch, merged)))))).embed).right,
            InternalError("unaligned union provenances").left)
      }

    case LogicalPlan.InvokeFUnapply(set.Intersect, Sized(a1, a2)) =>
      val merged: MergeResult = merge(a1.value, a2.value)

      Target(Ann(prov.joinProvenances(a1.ann.provenance, a2.ann.provenance), HoleF),
        TJ.inj(ThetaJoin(
          merged.src,
          merged.lval,
          merged.rval,
          Free.roll(Eq(Free.point(LeftSide), Free.point(RightSide))),
          Inner,
          LeftSideF)).embed).right

    case LogicalPlan.InvokeFUnapply(set.Except, Sized(a1, a2)) =>
      val merged: MergeResult = merge(a1.value, a2.value)

      Target(Ann(a1.ann.provenance, HoleF),
        TJ.inj(ThetaJoin(
          merged.src,
          merged.lval,
          merged.rval,
          BoolLit(false),
          LeftOuter,
          LeftSideF)).embed).right

    case LogicalPlan.InvokeFUnapply(func @ TernaryFunc(_, _, _, _, _, _, _, _), Sized(a1, a2, a3))
        if func.effect ≟ Transformation =>
      invokeThetaJoin(
        Func.Input3(a1, a2, a3),
        func match {
          case set.InnerJoin      => Inner
          case set.LeftOuterJoin  => LeftOuter
          case set.RightOuterJoin => RightOuter
          case set.FullOuterJoin  => FullOuter
        })
  }
}