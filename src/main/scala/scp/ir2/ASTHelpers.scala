package scp
package ir2

import lang2._
import utils._
import utils.meta.{RuntimeUniverseHelpers => ruh}
import ruh.sru


/** Useful utility methods used by AST */
trait ASTHelpers { self: AST =>
  
  
  // TODO implement and use `traverse` method here
  def hasHoles(r: Rep): Boolean = {
    transformRep(r){case RepDef(Hole(_)) => return true case RepDef(SplicedHole(_)) => return true case r => r}
    false
  }
  
  
  
  object Apply {
    val Symbol = ruh.FunctionType.symbol().toType.member(sru.TermName("apply"))
  }
  
  // TODO implement helpers to ease method loading : method[Tp](name) or method[Tp](_.mtd(???...)), or using QQ: ir"($$_:Int)+($$_:Int)".method?
  object Imperative {
    val Symbol = loadMtdSymbol(loadTypSymbol("scp.lib.package$"), "Imperative", None)
    def apply(effects: Rep*)(res: Rep) = if (effects isEmpty) res else
      rep(MethodApp(staticModule("scp.lib.package"), Symbol, res.typ::Nil, ArgsVarargs(Args(),Args(effects: _*))::Args(res)::Nil, res.typ))
    def unapply(r: Rep): Option[(Seq[Rep], Rep)] = unapply(dfn(r))
    def unapply(d: Def) = d match {
      case MethodApp(self, Symbol, _::Nil, ArgsVarargs(Args(),Args(effs @ _*))::Args(res)::Nil, _) => Some(effs, res)
      case _ => None
    }
  }
  
  object Var {
    val ModuleSymbol = loadTypSymbol("scp.lib.package.Var$")
    val ClassSymbol = loadTypSymbol("scp.lib.package.Var")
    object Apply {
      val Symbol = loadMtdSymbol(ModuleSymbol, "apply", None)
    }
    object Bang {
      val Symbol = loadMtdSymbol(ClassSymbol, "$bang", None)
    }
  }
  
  // Q: use a reinterpreter? (cf: would facilitate the work for Args)
  def prettyPrint(d: Def) = (new DefPrettyPrinter)(d)
  class DefPrettyPrinter {
    def apply(r: Rep): String = apply(dfn(r))
    def apply(d: Def): String = d match {
      case Constant(str: String) => '"' + str + '"'
      case Constant(v) => s"$v"
      case Typed(BoundVal(name), typ) => s"[$name:$typ]"
      case Abs(p, b) => s"{$p => ${apply(b)}}"
      case Imperative(effs, res) => s"{${effs map apply mkString "; "}; $res"
      case MethodApp(s, m, ts, ass, typ) => 
        val targsStr = if (ts isEmpty) "" else s"[${ts mkString ","}]"
        s"${apply(s)}.${m.name.decodedName}$targsStr${ass map (_ show apply) mkString ""}" + s"->$typ"
      case Module(pre, nam, tp) => s"${apply(pre)}.$nam"
      case StaticModule(fnam) => fnam
      case Ascribe(r,t) => s"${apply(r)}: $t"
      case Typed(Hole(name), typ) => s"$name: $typ"
      case Typed(SplicedHole(name), typ) => s"$name<:$typ*"
    }
  }
  
  
}

