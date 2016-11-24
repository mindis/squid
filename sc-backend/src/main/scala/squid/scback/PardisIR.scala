package squid
package scback

import ch.epfl.data.sc._
import ch.epfl.data.sc.pardis.ir.{Base => _, _}
import ch.epfl.data.sc.pardis.prettyprinter.ScalaCodeGenerator
import ch.epfl.data.sc.pardis.types.{PardisTypeImplicits, PardisType}
import pardis._
import pardis.{ir => pir}
import squid.utils._
import CollectionUtils.TraversableOnceHelper
import ch.epfl.data.sc.pardis.deep.scalalib.collection.CanBuildFromIRs.CanBuildFromType

import scala.collection.mutable
import meta.{RuntimeUniverseHelpers => ruh}
import meta.RuntimeUniverseHelpers.sru
import squid.ir.IRException
import squid.ir.{Covariant, Variance}
import squid.lang.Base
import squid.lang.InspectableBase
import sun.reflect.generics.reflectiveObjects.NotImplementedException

import scala.language.existentials
import scala.reflect.runtime.universe.TypeTag


/** IR Base that uses SC as a backend. It constructs PardisType's via reflective invocation of methods found in `ir`
  * Note: For convenience, we use Scala MethodSymbol and TypeSymbol to identify methods and types, but we could do with
  *   a simpler representation. */
abstract class PardisIR(val sc: pardis.ir.Base) extends Base with squid.ir.RuntimeSymbols with InspectableBase { self =>
  import sc.Def
  
  type Rep = ANFNode
  type BoundVal = sc.Sym[_]
  type TypeRep = sc.TypeRep[Any]
  type Expr = sc.Rep[_]
  type Block = sc.Block[_]
  type ABlock = sc.Block[Any]
  type Sym = sc.Sym[_]
  type ASym = sc.Sym[Any]
  type Stm = sc.Stm[_]
  type AStm = sc.Stm[Any]
  
  type R[+A] = sc.Rep[A]
  type TR[A] = sc.TypeRep[A]
  
  type TypSymbol = ScalaTypeSymbol
  
  
  case class New[A](_tp: TR[A]) extends Expression[A]()(_tp)
  
  sealed trait AnyHole[A] extends Expression[A] { val name: String }
  case class Hole[A](name: String, _tp: TR[A]) extends Expression[A]()(_tp) with AnyHole[A]
  case class SplicedHole[A](name: String, _tp: TR[A]) extends Expression[A]()(_tp) with AnyHole[A]
  
  case class HoleDef[A](h: AnyHole[A]) extends PardisNode[A]()(h.tp)
  
  
  case class TypeHole[A](name: String) extends PardisType[A] {
    def rebuild(newArguments: TR[_]*): TR[_] = this
    val typeArguments: List[TR[_]] = Nil
  }
  
  
  // TODO port to parent
  implicit val anyType: TR[Any] = types.AnyType
  def varargsToPardisVarargs = ???
  
  
  
  
  // * --- * --- * --- *  Implementations of `Base` methods  * --- * --- * --- *
  
  final val NAME_SUFFIX = "_$"
  def bindVal(name: String, typ: TypeRep, annots: List[Annot]): BoundVal = sc.freshNamed(name+NAME_SUFFIX)(typ)
  def readVal(v: BoundVal): Rep = curSubs.getOrElse(v, v)
  def const(value: Any): Rep = {
    import types.PardisTypeImplicits._
    value match {
      case value: Unit => sc.unit(value)
      case value: Boolean => sc.unit(value)
      case value: Int => sc.unit(value)
      case value: Double => sc.unit(value)
      case value: String => sc.unit(value)
      case null => sc.unit(null)
      case _ =>
        println("Unsupported constant value: "+value)
        ??? // TODO
    }
  }
  def lambda(params: List[BoundVal], body: => Rep): Rep = params match {
    case p :: Nil =>
      val b = typedBlock(toExpr(body))
      val d = sc.Lambda[Any,Any]((x: Rep) => ??? /*TODO*/ , p, b)(p.tp, b.tp)
      sc.toAtom(d)(types.PardisTypeImplicits.typeLambda1(p.tp, b.tp))
    case _ => ??? // TODO
  }
  override def letin(bound: BoundVal, value: Rep, body: => Rep, bodyType: TypeRep): Rep = {
    // we now put the result of let-bindings in blocks so their statements don't register before the stmts of Imperative
    // arguments that come before; this could be better solved with Imperative taking a by-name result (although it would
    // have the same effect); and better yet, having a Base method for imperative stuff (with scp.lib.Imperative only
    // used as a backup).
    typedBlock( 
      value match {
        case d: Def[_] =>  // Def <=> PardisNode
          sc.reflectStm(sc.Stm[Any](bound, d)(bound.tp))
          body
        case h:AnyHole[_] => // todo generalize
          val d = HoleDef(h)
          sc.reflectStm(sc.Stm[Any](bound, d)(bound.tp))
          body
        case e: Expr => withSubs(bound -> e)(body)
      }
    )
  }
  def newObject(tp: TypeRep): Rep = New(tp)
  def staticModule(fullName: String): Rep = null
  def module(prefix: Rep, name: String, typ: TypeRep): Rep = ???
  def byName(arg: => Rep): Rep = typedBlock(arg)
  
  object Const extends ConstAPI {
    def unapply[T: IRType](ir: IR[T, _]): Option[T] = ir.rep match {
      case cst @ pir.Constant(v) if cst.typ <:< irTypeOf[T].rep => Some(v.asInstanceOf[T])
      case _ => none
    }
  }
  
  def repEq(a: Rep, b: Rep): Boolean = a == b
  
  
  protected val curSubs = mutable.HashMap[Sym, Expr]()
  protected def withSubs[A](ss: (Sym -> Expr)*)(code: => A): A = {
    val keys = ss.unzip._1.toSet
    assert(curSubs.keySet intersect keys isEmpty)
    curSubs ++= ss
    try code
    finally curSubs --= keys
  }
  
  
  // Reimplementations
  
  override def showRep(r: Rep) = {
    import pardis.deep.scalalib.ArrayScalaCodeGen
    import ch.epfl.data.sc.pardis.prettyprinter.ASTCodeGenerator
    import ch.epfl.data.sc.pardis.utils.document.toDocumentContext
    val cg = new ScalaCodeGenerator with ASTCodeGenerator[sc.type] with ArrayScalaCodeGen {
      val IR: sc.type = sc
      override def expToDocument(exp: Expression[_]) = exp match {
        case Constant(b: Boolean) => doc"${b.toString}"
        case _                    => super.expToDocument(exp)
      }
    }
    r match {
      case b: Block => cg.blockToDocument(b).toString
      case d: PardisNode[_] => cg.nodeToDocument(d).toString
        
      // A specialization of the one below to get some additional type info:
      case d: ExpressionSymbol[_] => cg.symToDocument(d).toString + ": " + cg.tpeToDocument(d.tp)
      case d: PardisFunArg => cg.funArgToDocument(d).toString
        
      case cn =>
        //println(cn)
        r.toString
    }
  }
  
  
  // Helpers
  
  protected[squid] def typedBlock(body: => Rep): ABlock = {
    val sc.Block(s,r) = sc.reifyBlock[Any](toExpr(body))(types.AnyType)
    sc.Block(s,r)(r.tp)
  }
  protected def toAtom(r: sc.Def[_]) = sc.toAtom[Any](r)(r.tp.asInstanceOf[TR[Any]])
  protected def inlineBlock(b: Block) = {
    require(sc._IRReifier.scopeDepth > 0, s"No outer scope to inline into for $b")
    b.stmts.asInstanceOf[Seq[AStm]] foreach sc.reflectStm
    b.res
  }
  protected def inlineBlockIfEnclosed(b: Block) = {
    if (sc._IRReifier.scopeDepth > 0) {
      b.stmts.asInstanceOf[Seq[AStm]] foreach sc.reflectStm
      b.res
    } else b
  }
  protected def toExpr(r: Rep): Expr = r match {
    case r: Expr => r
    case b: Block => inlineBlock(b)
    case d: Def[_] => toAtom(d)
  }
  
  
  
  // * --- * --- * --- *  Implementations of `QuasiBase` methods  * --- * --- * --- *
  
  protected def notUnnecessaryBlock(r: Rep) = r |>=? {
    case b: Block => inlineBlockIfEnclosed(b)
  }
  override def substitute(r: Rep, defs: Map[String, Rep]): Rep = substituteLazy(r, defs.mapValues(() => _))
  override def substituteLazy(r: Rep, defs: Map[String, () => Rep]): Rep = {
    //debug(s"SUBST $r with "+(defs mapValues (f=>util.Try(typedBlock(f())))))
    
    val nameMap = curSubs collect { case k -> Hole(n,_) => k -> n }
    
    if (defs isEmpty) r else bottomUp(r) {
      case h @ Hole(n,_) => defs get n map (_()) getOrElse h
      case h @ SplicedHole(n,_) => defs get n map (_()) getOrElse h
      case s: Sym =>
        //println("Trav "+s, nameMap get s flatMap (defs get _) map (_()) getOrElse s)
        nameMap get s flatMap defs.get map (_()) getOrElse s
      case r => r
    }
    
  } |> notUnnecessaryBlock  //and (r => println(s"SUBS RESULT = $r"))
  
  def hole(name: String, typ: TypeRep): Rep = Hole(name, typ)
  def splicedHole(name: String, typ: TypeRep): Rep = SplicedHole(name, typ)
  def typeHole(name: String): TypeRep = TypeHole(name)
  
  
  
  // * --- * --- * --- *  Implementations of `TypingBase` methods  * --- * --- * --- *
  
  
  def uninterpretedType[A: TypeTag]: TypeRep = {
    //println("Unint: "+implicitly[TypeTag[A]])
    implicitly[TypeTag[A]].tpe match {
      case ruh.Any => types.AnyType
      case ruh.Nothing => types.NothingType.asInstanceOf[TypeRep]
      case typ =>
        throw new IRException(s"Unsupported uninterpreted type: `$typ`")
    }
  }
  def typeApp(self: TypeRep, typ: TypSymbol, targs: List[TypeRep]): TypeRep = {
    // This can happen in some cases, but is usually just a quirk
    null
  }
  def staticTypeApp(typ: TypSymbol, targs: List[TypeRep]): TypeRep = {
    //println(s"Type $typ $targs "+typ.isStatic)
    
    if (typ.isModuleClass) return null  // sometimes happen that we get `object Seq` (as an arg to CanBuildFrom), not sure why
    
    val (obj,tname) = typ.name.toString match {
      case "CanBuildFrom" =>
        val _::arg::coll::Nil = targs
        return CanBuildFromType(coll,arg,coll)
      case _ if targs.nonEmpty && typ === ruh.FunctionType.symbol(targs.size-1) => sc.Predef -> ("typeLambda"+(targs.size-1))
      case _ => sc -> ("type"+typ.name.toString)
    }
    
    try {
      val rec -> m = try obj -> obj.getClass.getMethod(tname, targs map (_ => classOf[TypeRep]): _*)
      catch { case e: NoSuchMethodException =>
        PardisTypeImplicits -> PardisTypeImplicits.getClass.getMethod(tname, targs map (_ => classOf[TypeRep]): _*)
      }
      val r = m.invoke(rec, targs: _*)
      r.asInstanceOf[TypeRep]
    } catch {
      case e: NoSuchMethodException =>
        throw new IRException(s"Could not find a deep type representation for type symbol `$typ` (tried name ${'"'+tname+'"'}); perhaps it is absent from the DSL cake.", Some(e))
    }
    
  }
  def recordType(fields: List[(String, TypeRep)]): TypeRep = ???
  def constType(value: Any, underlying: TypeRep): TypeRep = underlying
  
  // TODO refine? Basic subtyping like Int <: Any? also look out for non-covariance...
  def typLeq(a: TypeRep, b: TypeRep): Boolean =
    (a.name === b.name) && ((a.typeArguments zipAnd b.typeArguments)((typLeq _).asInstanceOf[(TR[_],TR[_])=>Bool]) forall identity)
  
  
  
  // * --- * --- * --- *  Implementations of `IntermediateBase` methods  * --- * --- * --- *
  
  
  def repType(r: Rep): TypeRep = r match {
    case r: PardisVar[_] => r.e.tp
    case r: PardisNode[_] => r.tp
    case r: Stm => r.typeT
    //case r: Hole[_] => r.tp
    // PardisFunArg:
    case r: PardisLambdaDef => ???
    case r: PardisVarArg =>
      // The underlying node of a PardisVarArg is a Seq(...), of Seq[T] type (we just want the T)
      r.underlying.typ.typeArguments.head
    case r: Expr => r.tp
    //case r: Sym => r.tp  // is an Expr
    //case r: Block => r.res.tp  // is a PNode
  }
  def boundValType(bv: BoundVal): TypeRep = bv.tp
  
  def reinterpret(r: Rep, newBase: Base)(extrudedHandle: (BoundVal => newBase.Rep) = DefaultExtrudedHandler): newBase.Rep =
    throw new NotImplementedException
  
  
  
  // * --- * --- * --- *  Implementations of `InspectableBase` methods  * --- * --- * --- *
  
  
  def bottomUp(r: Rep)(f: Rep => Rep): Rep = transformRep(r)(identity, f)
  def topDown(r: Rep)(f: Rep => Rep): Rep = transformRep(r)(f)
  def transformRep(r: Rep)(pre: Rep => Rep, post: Rep => Rep = identity): Rep = (new RepTransformer(pre,post))(r)
  
  // FIXME: probably doesn't work for things other than Block's: have to reify statements again (cf: global symbol map)
  // ^ TODOmaybe simply rebind things in the global map; we don't care about the old value of transformed stuff anyway (Q: is it okay with speculative Rw?)
  class RepTransformer(pre: Rep => Rep, post: Rep => Rep) {
    
    def apply(r: Rep): Rep = /*println(s"Apply: $r") before*/ post(pre(r) match {
    // PardisNode
        
      case b: Block => transformBlock(b)
        
      // Note: we do not try to transform the inputs to lambdas, as there is no Sym=>Sym mapping available
      // Q: should we renew the symbols?
      case PardisLambda0(f, o: ABlock) =>
        PardisLambda0[Any](() => ??? /* TODO */, o |> transformBlock)(o.tp)
      case pl @ PardisLambda(f, i, o: ABlock) =>
        PardisLambda[Any,Any]((_) => ??? /* TODO */, i, o |> transformBlock)(pl.typeT, pl.typeS)
      case pl @ PardisLambda2(f, i0, i1, o: ABlock) => ??? // TODO
      case pl @ PardisLambda3(f, i0, i1, i2, o: ABlock) => ??? // TODO
        
      case r: PardisNode[_] => 
        r.rebuild(r.funArgs map transformFunArg: _*)
        
    // PardisFunArg
        
      // Expr
        case cst: pir.Constant[_] => cst
        //case ex: ExpressionSymbol[_] => ex
        case ex: ExpressionSymbol[_] => curSubs.getOrElse(ex, ex)
        //case ex: ExpressionSymbol[_] => ex |> transformSym
        
      case PardisVarArg(v) => PardisVarArg(v |> transformFunArg)
      
      // TODO? PardisLambdaDef
        
    }) //and (r => println(s"Result: $r"))
    
    def transformFunArg: PardisFunArg => PardisFunArg = fa => fa |> apply |>=? { case PardisVarArg(v) => v } |> {
      // Q: can it happen that we transform a simple Rep into a Block, where the Block should not exist (since it's not a by-name arg)?
      // Just in case, check that the original node was already a Block, otherwise inline!
      case b: Block =>
        if (fa.isInstanceOf[Block]) b
        else b |> inlineBlock
      case fa: PardisFunArg => fa
      case x => x |> toExpr
    }
    
    def transformBlock(b: Block): ABlock = sc.reifyBlock[Any] {
      def rec(sts: List[Stm]): Unit = sts match {
        case sc.Stm(sym, rhs) :: sts =>
          apply(rhs) match {
            case d: Def[_] =>
              sc._IRReifier.findSymbol(d)(d.tp) getOrElse (sc.Stm(sym, d)(sym.tp) |> sc.reflectStm)
              // ^ Note: not renewing the symbol (I think it's unnecessary)
              rec(sts)
            case r: Rep =>
              val e = r |> toExpr
              withSubs(sym -> e)(rec(sts))
          }
        case Nil =>
        case _ => wtf // Scala spurious warning
      }
      rec(b.stmts)
      b.res |> apply |> toExpr
    }(b.tp)
    
  }
  
  protected def failExtrWith(msg: => String) = none oh_and debug(s"${Console.RED}$msg${Console.RESET}")
  
  override def merge(a: Extract, b: Extract): Option[Extract] =
    super.merge(a,b) >>? { case None => failExtrWith(s"Cannot merge: $a and $b") }  
  
  
  protected def extract(xtor: Rep, xtee: Rep): Option[Extract] = debug(s"${"Extr." |> bold} $xtor << $xtee") before nestDbg(xtor -> xtee match {
      
    case Hole(name, typ) -> _ => 
      typ extract (xtee.typ, Covariant) flatMap { merge(_, (Map(name -> xtee), Map(), Map())) }
      
    case (Constant(v1), Constant(v2)) =>
      mergeOpt(extractType(xtor.typ, xtee.typ, Covariant),
        if (v1 === v2) Some(EmptyExtract) else failExtrWith(s"Different constant values: $v1 =/= $v2"))
      
    // TODO proper impl of extraction
    case _ -> sc.Block(Nil, r1) => xtor extract r1
    case sc.Block(Nil, r0) -> _ => r0 extract xtee
    case sc.Block(s0, r0) -> sc.Block(s1, r1) => ???
      
    case Def(f0: pir.FunctionNode[_]) -> Def(f1: pir.FunctionNode[_]) if pure(f0) && pure(f1) =>
      extractDef(f0, f1)
      
    case (es0: ExpressionSymbol[_]) -> (es1: ExpressionSymbol[_]) if es0 == es1 => SomeEmptyExtract
    
    case (es0: ExpressionSymbol[_]) -> (es1: ExpressionSymbol[_]) if es1 |>? rwrCtx contains es0 => SomeEmptyExtract // Q: handle xted bindings here?

    case PardisVarArg(SplicedHole(name,typ)) -> PardisVarArg(Def(PardisLiftedSeq(seq))) =>
      typ extract (xtee.typ, Covariant) flatMap { merge(_, mkExtract()()(name -> seq)) }
      
    case _ => failExtrWith(s"No match.")
      
  })
  
  protected def extractDef(xtor: Def[_], xtee: Def[_]): Option[Extract] = {
    xtor -> xtee match {
      case (f0: pir.FunctionNode[_], f1: pir.FunctionNode[_]) =>
        if (f0.name =/= f1.name) failExtrWith(s"Different names: ${f0.name} =/= ${f1.name}")
        else for {
          e <- f0.caller -> f1.caller match {
            case Some(c0) -> Some(c1) => c0 extract c1
            case None -> None => SomeEmptyExtract
            case c0 -> c1 => throw IRException(s"Inconsistent callers: $c0 and $c1")
          }
          () = assert(f0.typeParams.size === f1.typeParams.size)
          ts <- mergeAll( (f0.typeParams zip f1.typeParams) map { case (a,b) => weakenTypeRep(a) extract (b, Covariant) } )
          e <- merge(e, ts)
          ass <- mergeAll( (f0.funArgs zipAnd f1.funArgs)(_ extract _) )
          e <- merge(e, ass)
        } yield e
      case (HoleDef(h), _) => extract(h, xtee)
      case (f0: pir.PardisNode[_], f1: pir.PardisNode[_]) =>
        if (f0.nodeName =/= f1.nodeName) failExtrWith(s"Different names: ${f0.nodeName} =/= ${f1.nodeName}")
        else mergeAll( (f0.funArgs zipAnd f1.funArgs)(_ extract _) )
      case (b0: Block, _) => b0 extract xtee
      case (_, b1: Block) => failExtrWith(s"Can't extract a Block with $xtor")
    }
  }
  
  protected def extractBlock(xtor: Block, xtee: Block): Option[Extract] = {
    /*
    //val left = xtor.stmts
    //def rec(ex: Extract, matchedVals: List[Sym])(xy: Block -> Block): Option[Rep] = /*println(s"rec $xy") before*/ (xy match {
    def rec(matchedHoles: List[BoundVal -> List[Stm]])(left: List[Stm], right: List[Stm]): Option[List[BoundVal -> List[Stm]] -> Extract] = left -> right match {
      //case (l :: ls) -> _ if d0(l) |> isHole => rec((l -> Nil) :: matchedHoles)(ls, right)
      //case (l :: ls) -> (r :: rs) => 
      case Nil -> Nil => matchedHoles -> EmptyExtract |> some
      case _ => ???
    }
    rec(Nil)(xtor.stmts, xtee.stmts)
    */
    ???
  }
  
  // TODO proper impl w/ cleanup
  // the xtor symbol a symbol has been matched with
  // extraction should make the corresponding xtor Stm match the Stm
  // substitution should apply based on associated Stm name
  type RwContext = PartialFunction[Sym, Sym]
  //def rwrCtx: RwContext = ???
  val rwrCtx = mutable.Map[Sym, Sym]()
  
  
  def bold(str: String) = {
    import Console.{BOLD, RESET}
    s"${BOLD}$str$RESET"
  }
  
  def pure(d: Def[_]) = d match {
    case d: PardisReadVal[_] => true
    case _ => d isPure
  }
  
  
  def varsIn(r: Rep): Set[Sym] = ???
  
  override def rewriteRep(xtor: Rep, xtee: Rep, code: Extract => Option[Rep]): Option[Rep] = /*ANFDebug muteFor*/ {
    debug(s"${"Rewriting" |> bold} $xtee ${"with" |> bold} $xtor")
    
    xtee >>? { // TODO (?) :
      case (_:PardisVarArg)|(_:PardisLiftedSeq[_]) => return None
    }
    
    def codeBlock(ex: Extract): Option[Block] = try typedBlock(code(ex) getOrElse (
      return failExtrWith("Rewritten code could not be constructed successfully: returned None"))
    ) |> some and (c => debug(s"Constructed code: ${c.get}")) catch {
      case e: Throwable =>
        failExtrWith(s"Rewritten code could not be constructed successfully: threw $e")
        throw e }
    
    type Val = Sym
    type ListBlock = List[AStm] -> Expr
    
    def constructBlock(lb: ListBlock): ABlock = sc.reifyBlock[Any] {
      lb._1 foreach sc.reflectStm[Any]
      lb._2
    }(lb._2.tp)
    
    def toHole(from: Sym, to: Sym): Extract = {
      debug(s"TODO Should bind $from -> $to")
      // TODO handle xted bindings!
      rwrCtx += from -> to // TODO cleanup, make reentrant
      EmptyExtract
    }
    
    /** Tries to apply a RwR on a list of statements by trying to match the xtor (itself a list of statements),
      * then applying the associated `code` function and checking removed statements are not used later on in the list.
      * Note: we accumulate skipped pure statements in `pureStms` because we need to look at them to see if they refer to removed nodes
      * @return pure statements encountered and skipped, rewritten block output by `code`, rest of the statements */
    def rec(ex: Extract, matchedVals: List[Val -> Val], pureStms: List[AStm])(xy: ListBlock -> ListBlock): Option[(List[Stm], Block -> Option[Val], ListBlock)] =
    debug(s"rec ${xy._1}\n<<  ${xy._2._1.headOption filter (_.rhs.isPure) map (_ => "[PURE]") getOrElse "" }${xy._2}") before nestDbg(xy match {
        
      // Note: placing this here makes it very eager; it will skip pure statements instead of matching a $body xtor (not ideal)
      case (x, ((ps @ sc.Stm(_, e1)) :: es1) -> r1) if (e1 |> pure)
        && matchedVals.nonEmpty // don't want to start skipping defs before the xtion has even begun
        && x._1.nonEmpty // don't want to ignore statements at the end just before the return – TODO this calls for a more general solution 
      => rec(ex, matchedVals, ps :: pureStms)(x, es1 -> r1)
        
      case ((sc.Stm(b0, e0) :: es0) -> r0, (sc.Stm(b1, e1) :: es1) -> r1) =>
        for {
          e <- extractDef(e0, e1)
          e <- merge(e, ex)
          hExtr = toHole(b1, b0)
          e <- merge(e, hExtr)
          
          // Try to match the current xtor binding with the corresponding xtee binding, and proceed with the rest
          r <- rec(e, b0 -> b1 :: matchedVals, pureStms)(es0 -> r0, es1 -> r1)
          
        } yield r
        
      case (Nil -> r0, bl @ sts -> r1) =>
        
        // Function to check that a set of bound vals are not used in a list of statements,
        // trying to remove pure statements referring to them, and transitively
        def removeStmts(acc: List[AStm])(toRm: Set[Val], from: List[AStm], fromRet: Expr): Option[List[AStm]] = debug(s"To remove: $toRm from $from ret $fromRet") before from match {
          case st :: stms =>
            val refs = freeVars(st.rhs)
            //debugVisible(s"Free vars $refs in ${st.rhs}")
            if (refs intersect toRm nonEmpty) {
              if (st.rhs |> pure) debug(s"Getting rid of pure ${st.sym}") before removeStmts(acc)(toRm + st.sym, stms, fromRet)
              else failExtrWith(s"Statement ${st.sym} references symbols in the remove set ${toRm}")
            } else removeStmts(st :: acc)(toRm, stms, fromRet)
          case Nil if fromRet |>? {case s:Sym => toRm(s)} forall (!_) => 
            Some(acc.reverse)
          case Nil => failExtrWith(s"Returned symbol ${fromRet} is in the remove set ${toRm}")
        }
        
        debug(s"Matched $matchedVals; ret $r1")
        debug(s"Skipped Pure $pureStms")
        
        // In case we're at the end of the xtor block, try to use its result to extrac the whole of the remaining xtee
        // This implements eager matching, which allow things like `case ir" ... ; $body: $t "` to work and extract the full body.
        (for {
          
          e <- extract(r0, bl |> constructBlock)
          m <- merge(e, ex)
          () = debug(s"${Console.GREEN}Constructing bin-rewritten code with $m${Console.RESET}")
          bindings = matchedVals collect {
            case a -> b if a.name contains NAME_SUFFIX =>
              b -> Hole(a.name splitAt (a.name indexOf NAME_SUFFIX) _1, b.tp)
          }
          //() = debug("Bindings: "+bindings)
          b <- withSubs(bindings:_*) {codeBlock(m)} // TODO do that in other cases too!
          
          //pureStmsR = pureStms.reverse  // makes typechecking fail with arcane error...
          _ <- removeStmts(Nil)(matchedVals.unzip._2.toSet, pureStms/*.reverse*/ ++ b.stmts.asInstanceOf[List[AStm]], b.res)
          newPureStms <- removeStmts(Nil)(matchedVals.unzip._2.toSet, pureStms.reverse, b.res)
          // ^ Note: `pureStms` passed here are not correctly ordered and may refer to things bound later,
          // but they'll be ignored and only reintroduced if necessary at the end of the rewriteRep algo (TODO)
          
        } yield (newPureStms, b -> (r1 |>? {case s: Sym => s}), Nil -> b.res)
          //                                                    ^ Nil here because the entire end of the block was matched
        ) orElse { if (r0 |> isHole) None else { // If we're not in a $body-result xtor – we're not going to match the result, so it would be unsound if it had a hole
          // If the above did not work, try only matching statements up to where we are currently in the xtee
          // This implements sequential statements rewriting, which can operate in the middle of blocks
          
          val matchedRet = matchedVals find (_._1 == r0) map (_._2)
          //val matchedRet = matchedVals collect { case `r0`->bv => bv }  // Crashes Scalac!!
          
          debug(s"Matched $matchedVals; ret $r1")
          debug(s"MatchedRet $matchedRet")
          debug(s"${Console.GREEN}About to construct seq-rewritten code with $ex${Console.RESET}")
          
          removeStmts(Nil)(matchedVals.unzip._2.toSet -- matchedRet, pureStms ++ sts, r1) flatMap { newStms =>
            // ^ Note: `pureStms` passed here are not correctly ordered and may refer to things bound later,
            // but they'll be ignored and only reintroduced if necessary at the end of the rewriteRep algo
            
            removeStmts(Nil)(matchedVals.unzip._2.toSet -- matchedRet, pureStms.reverse, r1) flatMap { newPureStms =>
            
              codeBlock(ex) map { bloc => (newPureStms, bloc -> matchedRet, newStms -> r1) }
              
            }
            
          }  and (r => debug("Maybe rewrote sequence to "+r))
        }}
        
      case _ =>
        debug("Rec reached default case")
        None
        
    })
    
    def toBlock(x: Rep): Bool -> ListBlock = x match {
      case sc.Block(sts:List[AStm @unchecked], r) => true -> (sts -> r)
      case r: Expr => false -> (Nil -> r)
      case d: Def[_] =>
        val f = sc.fresh(d.tp)
        false -> ((sc.Stm[Any](f,d)(f.tp)::Nil) -> f)
    }
    
    def process(xtor: ListBlock, xtee: ListBlock): List[Stm \/ (Block -> Option[Val])] = {
      rec(EmptyExtract, Nil, Nil)(xtor, xtee) match {
        case Some((ps, b -> v, lb)) => 
          //debugVisible(ps)
          //Right(b -> v) +: process(xtor, lb)
          // FIXME: not really correct to use `ps` here:
          ((ps map Left.apply) :+ Right(b -> v)) ++ process(xtor, lb)
        case None => xtee match {
          case (st :: sts) -> r => Left(st) :: process(xtor, sts -> r)  // Note: no need to pass around the return?
          case Nil -> r => Nil
        }
      }
    }
    val (xteeWasBlock, xteeBlock) = xtee |> toBlock
    val processed = process(xtor |> toBlock _2, xteeBlock)
    
    
    if (processed forall (_ isLeft)) none  // No RwR have kicked in
    else {
      
      val sub = mutable.Map[Val,Rep]()
      def addBinding(ft: Val -> Rep) = {  // lol, changing Rep to Expr crashes Scalac
        sub += ft
        // Support for transitivity:  (Q: really needed?)
        for (f -> ft._1 <- sub; ft._2 -> t2 <- sub if t2 =/= ft._2) sub += f -> t2
      }
      
      val b = sc.reifyBlock[Any] {
        // TODO reintroduce pure stmts
        processed foreach {
          case Left(s: Stm) => sc.reflectStm(s)//(s.)
          case Right(bl -> v) =>
            v foreach (_ -> bl.res |> addBinding)
            //debug(s"Inlining with substitutions $sub, block: $bl")
            bl.stmts foreach (s => sc.reflectStm(s))
        }
        xteeBlock._2
      }(xtee.typ)
      
      //debug(s"Mapping: $sub")
      //debug(s"Pre: $b")
      
      // Update all the references to rewritten statements blocks:
      val b2 = bottomUpPartial(b){ case s:Sym => sub.getOrElse(s, s) }
      
      //debug(s"Post: $b2")
      
      // Not sure really useful to make a distinction here; used to always wrap (then it'd get inlined later anyways)
      val r = if (xteeWasBlock) b2
        else b2 |> toExpr // to inline the Block if necessary
      
      //debug(s"Res: $r")
      
      r
      
    } |> some and (r => debug(s"${"Rewrote:" |> bold} $r"))
    
  }
  
  
  
  protected def spliceExtract(xtor: Rep, t: Args): Option[Extract] = ???

  // TODO refine? Basic subtyping like Int <: Any?
  def extractType(xtor: TypeRep, xtee: TypeRep, va: Variance): Option[Extract] = debug(s"$va ${s"TypExtr." |> bold} $xtor << $xtee") before nestDbg(xtor match {
    case TypeHole(name) => mkExtract()(name -> xtee)() |> some
    case _ =>
      val xtorNameBase = xtor |> baseName
      val xteeNameBase = xtee |> baseName
      if (xtorNameBase =/= xteeNameBase) failExtrWith(s"Different type names: ${xtorNameBase} =/= ${xteeNameBase}")
      else {
        assert(xtor.typeArguments.size === xtee.typeArguments.size)
        mergeAll((xtor.typeArguments zipAnd xtee.typeArguments)(extractType(_,_,va))) // TODO proper handling of va
      }
  })
  protected def baseName(tp: TypeRep) = tp.name takeWhile (_ =/= '[')
  
  
  /** Extraction nodes need to be wrapped in a Block, because it makes no sense to let statements escape the pattern. */
  override def wrapExtract(r: => Rep): Rep = typedBlock(r)
  /** In case there is no enclosing block, just create one instead of crashing!
    * If the resulting Block node is inserted as an expression later in a tree, it will be inlined anyway. */
  override def wrapConstruct(r: => Rep): Rep =
    if (sc._IRReifier.scopeDepth == 0) typedBlock(r)
    else r |> toExpr  // inlines blocks and calls toAtom on Def's
  
  
  
  
  // * --- * --- * --- *  Other Implementations * --- * --- * --- *
  
  
  protected def traversePartial(f: PartialFunction[Rep, Boolean]) = traverse(f orElse PartialFunction(_ => true)) _
  
  protected def traverse(f: Rep => Boolean)(r: Rep): Unit = {
    val continue = f(r)
    val rec = if (continue) traverse(f) _ else ignore
    r match {
    // PardisNode
        
      case b: Block => if (continue) b.stmts foreach rec before b.res |> rec
        
      case sc.Stm(s, o) => o |> rec
        
      case PardisLambda0(f, o: ABlock) => o |> rec
      case PardisLambda(f, i, o: ABlock) => o |> rec
      case PardisLambda2(f, i0, i1, o: ABlock) => o |> rec
      case PardisLambda3(f, i0, i1, i2, o: ABlock) => o |> rec
        
      case r: PardisNode[_] => if (continue) r.funArgs foreach rec
        
    // PardisFunArg
        
      // Expr
        case cst: pir.Constant[_] =>
        case ex: ExpressionSymbol[_] =>
        
      case PardisVarArg(und) => und |> rec
        
      // TODO? PardisLambdaDef
        
    }
  }
  
  protected def freeVars(r: Rep): collection.Set[BoundVal] = {
    // FIXME Q: can there ever be the same sym bound several times? (cf block inlining)
    val referenced = mutable.Set[BoundVal]()
    val bound = mutable.Set[BoundVal]()
    r |> traversePartial {
      case s: Sym => referenced += s; true
      case sc.Stm(s,rhs) => bound += s; true
      case PardisLambda(f, i: ASym @unchecked, o: ABlock) => bound += i; true
      case PardisLambda2(f, i0: ASym @unchecked, i1: ASym @unchecked, o: ABlock) => bound += i0; bound += i1; true
      case PardisLambda3(f, i0: ASym @unchecked, i1: ASym @unchecked, i2: ASym @unchecked, o: ABlock) => bound += i0; bound += i1; bound += i2; true
    }
    referenced -- bound
  }
  
  
  protected def isHole(r: R[Any]) = r |>? { case Hole(_,_)|SplicedHole(_,_) => } isDefined
  
  def block[T:IRType,C](q: => IR[T,C]) = `internal IR`[T,C](pardisBlock[T,C](q))
  def pardisBlock[T:IRType,C](q: => IR[T,C]) = sc.reifyBlock[T] { toExpr(q.rep).asInstanceOf[R[T]] }
  
  
  implicit def typeRepFromIRType[A:IRType]: sc.TypeRep[A] = implicitly[IRType[A]].rep.asInstanceOf[sc.TypeRep[A]]
  
  implicit def weakenTypeRep(tr: TR[_]): TypeRep = tr.asInstanceOf[TypeRep]
  
  
}


