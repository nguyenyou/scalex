use rustc_hash::FxHashMap;
use crate::model::proto;

/// Print a SymbolInformation as a human-readable signature string.
/// This is a port of Scalameta's SymbolInformationPrinter.
pub fn print_info(
    info: &proto::SymbolInformation,
    symtab: &FxHashMap<String, &proto::SymbolInformation>,
) -> String {
    let mut out = String::new();
    let mut printer = InfoPrinter { out: &mut out, symtab };
    printer.pprint_top(info);
    out
}

/// Print a Type as a human-readable string.
pub fn print_type(
    tpe: &proto::Type,
    symtab: &FxHashMap<String, &proto::SymbolInformation>,
) -> String {
    let mut out = String::new();
    let mut printer = InfoPrinter { out: &mut out, symtab };
    printer.pprint_type_normal(tpe);
    out
}

struct InfoPrinter<'a> {
    out: &'a mut String,
    symtab: &'a FxHashMap<String, &'a proto::SymbolInformation>,
}

impl<'a> InfoPrinter<'a> {
    fn pprint_top(&mut self, info: &proto::SymbolInformation) {
        // Print the full `modifiers kind Name signature` format matching sdbex's Print.info
        self.pprint_info(info);
    }

    /// Print full symbol info: modifiers, kind, name, signature (matches Scalameta's pprint(info))
    fn pprint_info(&mut self, info: &proto::SymbolInformation) {
        use proto::symbol_information::Kind;
        let props = info.properties as u32;
        // Modifiers
        if props & 0x4 != 0 { self.out.push_str("abstract "); }
        if props & 0x8 != 0 { self.out.push_str("final "); }
        if props & 0x10 != 0 { self.out.push_str("sealed "); }
        if props & 0x20 != 0 { self.out.push_str("implicit "); }
        if props & 0x40 != 0 { self.out.push_str("lazy "); }
        if props & 0x80 != 0 { self.out.push_str("case "); }
        if props & 0x100 != 0 { self.out.push_str("covariant "); }
        if props & 0x200 != 0 { self.out.push_str("contravariant "); }
        if props & 0x400 != 0 { self.out.push_str("val "); }
        if props & 0x800 != 0 { self.out.push_str("var "); }
        if props & 0x1000 != 0 { self.out.push_str("static "); }
        if props & 0x2000 != 0 { self.out.push_str("primary "); }
        if props & 0x4000 != 0 { self.out.push_str("enum "); }
        if props & 0x8000 != 0 { self.out.push_str("default "); }
        if props & 0x10000 != 0 { self.out.push_str("given "); }
        if props & 0x20000 != 0 { self.out.push_str("inline "); }
        if props & 0x40000 != 0 { self.out.push_str("open "); }
        if props & 0x80000 != 0 { self.out.push_str("transparent "); }
        if props & 0x100000 != 0 { self.out.push_str("infix "); }
        if props & 0x200000 != 0 { self.out.push_str("opaque "); }
        // Kind keyword
        match Kind::try_from(info.kind) {
            Ok(Kind::Local) => self.out.push_str("local "),
            Ok(Kind::Field) => self.out.push_str("field "),
            Ok(Kind::Method) => self.out.push_str("method "),
            Ok(Kind::Constructor) => self.out.push_str("ctor "),
            Ok(Kind::Macro) => self.out.push_str("macro "),
            Ok(Kind::Type) => self.out.push_str("type "),
            Ok(Kind::Parameter) => self.out.push_str("param "),
            Ok(Kind::SelfParameter) => self.out.push_str("selfparam "),
            Ok(Kind::TypeParameter) => self.out.push_str("typeparam "),
            Ok(Kind::Object) => self.out.push_str("object "),
            Ok(Kind::Package) => self.out.push_str("package "),
            Ok(Kind::PackageObject) => self.out.push_str("package object "),
            Ok(Kind::Class) => self.out.push_str("class "),
            Ok(Kind::Trait) => self.out.push_str("trait "),
            Ok(Kind::Interface) => self.out.push_str("interface "),
            _ => self.out.push_str("unknown "),
        }
        // Name
        if info.display_name.is_empty() {
            self.out.push_str("<?>");
        } else {
            self.out.push_str(&info.display_name);
        }
        // Signature
        if let Some(ref sig) = info.signature {
            if !(info.kind == Kind::SelfParameter as i32 && sig.sealed_value.is_none()) {
                let prefix = prefix_before_tpe(info);
                if !prefix.is_empty() {
                    // For fields/params, insert ": " before the signature
                    self.pprint_signature_with_prefix(sig, info, prefix);
                } else {
                    self.pprint_signature(sig, info);
                }
            }
        }
    }

    fn pprint_signature_with_prefix(&mut self, sig: &proto::Signature, info: &proto::SymbolInformation, prefix: &str) {
        let Some(ref sv) = sig.sealed_value else { return };
        match sv {
            proto::signature::SealedValue::ValueSignature(vs) => {
                self.out.push_str(prefix);
                if let Some(ref tpe) = vs.tpe {
                    self.pprint_type_normal(tpe);
                }
            }
            _ => self.pprint_signature(sig, info),
        }
    }

    fn pprint_signature(&mut self, sig: &proto::Signature, info: &proto::SymbolInformation) {
        let Some(ref sv) = sig.sealed_value else { return };
        match sv {
            proto::signature::SealedValue::ClassSignature(cs) => {
                self.pprint_tparams(cs.type_parameters.as_ref());
                self.pprint_parents(&cs.parents);
                // Print self type and declaration count like sdbex
                let has_self = cs.self_.as_ref().map_or(false, |t| t.sealed_value.is_some());
                let decl_count = cs.declarations.as_ref().map_or(0, |d| d.hardlinks.len() + d.symlinks.len());
                if has_self || decl_count > 0 {
                    self.out.push_str(" { ");
                    if has_self {
                        self.out.push_str("self: ");
                        if let Some(ref self_tpe) = cs.self_ {
                            self.pprint_type_normal(self_tpe);
                        }
                        self.out.push_str(" => ");
                    }
                    if decl_count > 0 {
                        self.out.push_str(&format!("+{} decls", decl_count));
                    }
                    self.out.push_str(" }");
                }
            }
            proto::signature::SealedValue::MethodSignature(ms) => {
                self.pprint_tparams(ms.type_parameters.as_ref());
                for params in &ms.parameter_lists {
                    self.out.push('(');
                    let infos = self.scope_infos(params);
                    for (i, p_info) in infos.iter().enumerate() {
                        if i > 0 { self.out.push_str(", "); }
                        self.pprint_defn(p_info);
                    }
                    self.out.push(')');
                }
                if let Some(ref ret) = ms.return_type {
                    self.out.push_str(": ");
                    self.pprint_type_normal(ret);
                }
            }
            proto::signature::SealedValue::TypeSignature(ts) => {
                self.pprint_tparams(ts.type_parameters.as_ref());
                let lo = ts.lower_bound.as_ref();
                let hi = ts.upper_bound.as_ref();
                if lo != hi {
                    if let Some(lo) = lo {
                        if !is_nothing(lo) {
                            self.out.push_str(" >: ");
                            self.pprint_type_normal(lo);
                        }
                    }
                    if let Some(hi) = hi {
                        if !is_any(hi) && !is_object(hi) {
                            self.out.push_str(" <: ");
                            self.pprint_type_normal(hi);
                        }
                    }
                } else if let Some(lo) = lo {
                    self.out.push_str(" = ");
                    self.pprint_type_normal(lo);
                }
            }
            proto::signature::SealedValue::ValueSignature(vs) => {
                let prefix = prefix_before_tpe(info);
                if !prefix.is_empty() {
                    self.out.push_str(prefix);
                }
                if let Some(ref tpe) = vs.tpe {
                    self.pprint_type_normal(tpe);
                }
            }
        }
    }

    fn pprint_type_normal(&mut self, tpe: &proto::Type) {
        let Some(ref sv) = tpe.sealed_value else { return };
        match sv {
            proto::r#type::SealedValue::SingleType(_)
            | proto::r#type::SealedValue::ThisType(_)
            | proto::r#type::SealedValue::SuperType(_) => {
                self.pprint_type_prefix(tpe);
                self.out.push_str(".type");
            }
            _ => self.pprint_type_prefix(tpe),
        }
    }

    fn pprint_type_prefix(&mut self, tpe: &proto::Type) {
        let Some(ref sv) = tpe.sealed_value else { return };
        match sv {
            proto::r#type::SealedValue::TypeRef(tr) => {
                if let Some(ref pre) = tr.prefix {
                    if let Some(ref psv) = pre.sealed_value {
                        match psv {
                            proto::r#type::SealedValue::SingleType(_)
                            | proto::r#type::SealedValue::ThisType(_)
                            | proto::r#type::SealedValue::SuperType(_) => {
                                self.pprint_type_prefix(pre);
                                self.out.push('.');
                            }
                            _ if has_value(pre) => {
                                self.pprint_type_prefix(pre);
                                self.out.push('#');
                            }
                            _ => {}
                        }
                    }
                }
                self.pprint_ref(&tr.symbol);
                if !tr.type_arguments.is_empty() {
                    self.out.push('[');
                    for (i, arg) in tr.type_arguments.iter().enumerate() {
                        if i > 0 { self.out.push_str(", "); }
                        self.pprint_type_normal(arg);
                    }
                    self.out.push(']');
                }
            }
            proto::r#type::SealedValue::SingleType(st) => {
                if let Some(ref pre) = st.prefix {
                    if has_value(pre) {
                        self.pprint_type_prefix(pre);
                        self.out.push('.');
                    }
                }
                self.pprint_ref(&st.symbol);
            }
            proto::r#type::SealedValue::ThisType(tt) => {
                if !tt.symbol.is_empty() {
                    self.pprint_ref(&tt.symbol);
                    self.out.push('.');
                }
                self.out.push_str("this");
            }
            proto::r#type::SealedValue::SuperType(st) => {
                if let Some(ref pre) = st.prefix {
                    if has_value(pre) {
                        self.pprint_type_prefix(pre);
                        self.out.push('.');
                    }
                }
                self.out.push_str("super");
                if !st.symbol.is_empty() {
                    self.out.push('[');
                    self.pprint_ref(&st.symbol);
                    self.out.push(']');
                }
            }
            proto::r#type::SealedValue::ConstantType(ct) => {
                if let Some(ref c) = ct.constant {
                    self.pprint_constant(c);
                }
            }
            proto::r#type::SealedValue::IntersectionType(it) => {
                for (i, t) in it.types.iter().enumerate() {
                    if i > 0 { self.out.push_str(" & "); }
                    self.pprint_type_normal(t);
                }
            }
            proto::r#type::SealedValue::UnionType(ut) => {
                for (i, t) in ut.types.iter().enumerate() {
                    if i > 0 { self.out.push_str(" | "); }
                    self.pprint_type_normal(t);
                }
            }
            proto::r#type::SealedValue::WithType(wt) => {
                for (i, t) in wt.types.iter().enumerate() {
                    if i > 0 { self.out.push_str(" with "); }
                    self.pprint_type_normal(t);
                }
            }
            proto::r#type::SealedValue::StructuralType(st) => {
                if let Some(ref utpe) = st.tpe {
                    self.pprint_type_normal(utpe);
                }
                if let Some(ref decls) = st.declarations {
                    if !decls.hardlinks.is_empty() {
                        self.out.push_str(" { ");
                        for (i, info) in decls.hardlinks.iter().enumerate() {
                            if i > 0 { self.out.push_str("; "); }
                            self.pprint_defn(info);
                        }
                        self.out.push_str(" }");
                    } else {
                        self.out.push_str(" {}");
                    }
                } else {
                    self.out.push_str(" {}");
                }
            }
            proto::r#type::SealedValue::AnnotatedType(at) => {
                if let Some(ref utpe) = at.tpe {
                    self.pprint_type_normal(utpe);
                }
                for ann in &at.annotations {
                    self.out.push(' ');
                    self.out.push('@');
                    if let Some(ref tpe) = ann.tpe {
                        self.pprint_type_normal(tpe);
                    }
                }
            }
            proto::r#type::SealedValue::ExistentialType(et) => {
                if let Some(ref utpe) = et.tpe {
                    self.pprint_type_normal(utpe);
                }
                if let Some(ref decls) = et.declarations {
                    if !decls.hardlinks.is_empty() {
                        self.out.push_str(" forSome { ");
                        for (i, info) in decls.hardlinks.iter().enumerate() {
                            if i > 0 { self.out.push_str("; "); }
                            self.pprint_defn(info);
                        }
                        self.out.push_str(" }");
                    }
                }
            }
            proto::r#type::SealedValue::UniversalType(ut) => {
                if let Some(ref tparams) = ut.type_parameters {
                    if !tparams.hardlinks.is_empty() {
                        self.out.push('[');
                        for (i, info) in tparams.hardlinks.iter().enumerate() {
                            if i > 0 { self.out.push_str(", "); }
                            self.pprint_defn(info);
                        }
                        self.out.push_str("] => ");
                    }
                }
                if let Some(ref utpe) = ut.tpe {
                    self.pprint_type_normal(utpe);
                }
            }
            proto::r#type::SealedValue::ByNameType(bt) => {
                self.out.push_str("=> ");
                if let Some(ref utpe) = bt.tpe {
                    self.pprint_type_normal(utpe);
                }
            }
            proto::r#type::SealedValue::RepeatedType(rt) => {
                if let Some(ref utpe) = rt.tpe {
                    self.pprint_type_normal(utpe);
                }
                self.out.push('*');
            }
            proto::r#type::SealedValue::MatchType(mt) => {
                if let Some(ref scrutinee) = mt.scrutinee {
                    self.pprint_type_normal(scrutinee);
                }
                self.out.push_str(" match { ");
                for (i, case) in mt.cases.iter().enumerate() {
                    if i > 0 { self.out.push_str(", "); }
                    if let Some(ref key) = case.key {
                        self.pprint_type_normal(key);
                    }
                    self.out.push_str(" => ");
                    if let Some(ref body) = case.body {
                        self.pprint_type_normal(body);
                    }
                }
                self.out.push_str(" }");
            }
            proto::r#type::SealedValue::LambdaType(lt) => {
                if let Some(ref params) = lt.parameters {
                    if !params.hardlinks.is_empty() {
                        self.out.push('[');
                        for (i, info) in params.hardlinks.iter().enumerate() {
                            if i > 0 { self.out.push_str(", "); }
                            self.pprint_defn(info);
                        }
                        self.out.push_str("] =>> ");
                    }
                }
                if let Some(ref ret) = lt.return_type {
                    self.pprint_type_normal(ret);
                }
            }
        }
    }

    fn pprint_constant(&mut self, constant: &proto::Constant) {
        let Some(ref sv) = constant.sealed_value else { return };
        match sv {
            proto::constant::SealedValue::UnitConstant(_) => self.out.push_str("()"),
            proto::constant::SealedValue::BooleanConstant(c) => {
                self.out.push_str(if c.value { "true" } else { "false" });
            }
            proto::constant::SealedValue::ByteConstant(c) => {
                self.out.push_str(&(c.value as i8).to_string());
            }
            proto::constant::SealedValue::ShortConstant(c) => {
                self.out.push_str(&(c.value as i16).to_string());
            }
            proto::constant::SealedValue::CharConstant(c) => {
                self.out.push('\'');
                self.out.push(char::from_u32(c.value as u32).unwrap_or('?'));
                self.out.push('\'');
            }
            proto::constant::SealedValue::IntConstant(c) => {
                self.out.push_str(&c.value.to_string());
            }
            proto::constant::SealedValue::LongConstant(c) => {
                self.out.push_str(&c.value.to_string());
                self.out.push('L');
            }
            proto::constant::SealedValue::FloatConstant(c) => {
                self.out.push_str(&c.value.to_string());
                self.out.push('f');
            }
            proto::constant::SealedValue::DoubleConstant(c) => {
                self.out.push_str(&c.value.to_string());
            }
            proto::constant::SealedValue::StringConstant(c) => {
                self.out.push('"');
                self.out.push_str(&c.value);
                self.out.push('"');
            }
            proto::constant::SealedValue::NullConstant(_) => self.out.push_str("null"),
        }
    }

    fn pprint_ref(&mut self, sym: &str) {
        if sym.is_empty() { return; }
        let info = self.symtab.get(sym);
        let name = info
            .map(|i| i.display_name.as_str())
            .filter(|n| !n.is_empty())
            .unwrap_or_else(|| crate::symbol::symbol_display_name(sym));
        self.out.push_str(name);
    }

    fn pprint_defn(&mut self, info: &proto::SymbolInformation) {
        use proto::symbol_information::Kind;
        // Print modifiers
        let props = info.properties as u32;
        if props & 0x4 != 0 && info.kind == Kind::Class as i32 { self.out.push_str("abstract "); }
        if props & 0x8 != 0 && info.kind != Kind::Object as i32 { self.out.push_str("final "); }
        if props & 0x10 != 0 { self.out.push_str("sealed "); }
        if props & 0x20 != 0 { self.out.push_str("implicit "); }
        if props & 0x40 != 0 { self.out.push_str("lazy "); }
        if props & 0x80 != 0 { self.out.push_str("case "); }
        if props & 0x100 != 0 { self.out.push('+'); }
        if props & 0x200 != 0 { self.out.push('-'); }
        if props & 0x400 != 0 { self.out.push_str("val "); }
        if props & 0x800 != 0 { self.out.push_str("var "); }
        if props & 0x1000 != 0 { self.out.push_str("static "); }
        if props & 0x4000 != 0 { self.out.push_str("enum "); }
        // Kind keyword
        match Kind::try_from(info.kind) {
            Ok(Kind::Method) | Ok(Kind::Constructor) => self.out.push_str("def "),
            Ok(Kind::Macro) => self.out.push_str("macro "),
            Ok(Kind::Type) => self.out.push_str("type "),
            Ok(Kind::Object) => self.out.push_str("object "),
            Ok(Kind::Package) => self.out.push_str("package "),
            Ok(Kind::PackageObject) => self.out.push_str("package object "),
            Ok(Kind::Class) => self.out.push_str("class "),
            Ok(Kind::Trait) => self.out.push_str("trait "),
            Ok(Kind::Interface) => self.out.push_str("interface "),
            _ => {} // Field, Parameter, TypeParameter, Local — no keyword
        }
        // Name
        let name = &info.display_name;
        if name.is_empty() {
            self.out.push_str("<?>");
        } else {
            self.out.push_str(name);
        }
        // Signature
        if let Some(ref sig) = info.signature {
            self.pprint_signature(sig, info);
        }
    }

    fn pprint_tparams(&mut self, scope: Option<&proto::Scope>) {
        let Some(scope) = scope else { return };
        let infos = self.scope_infos(scope);
        if infos.is_empty() { return; }
        self.out.push('[');
        for (i, info) in infos.iter().enumerate() {
            if i > 0 { self.out.push_str(", "); }
            self.pprint_defn(info);
        }
        self.out.push(']');
    }

    fn pprint_parents(&mut self, parents: &[proto::Type]) {
        if parents.is_empty() { return; }
        self.out.push_str(" extends ");
        for (i, p) in parents.iter().enumerate() {
            if i > 0 { self.out.push_str(" with "); }
            self.pprint_type_normal(p);
        }
    }

    /// Resolve a Scope to a list of SymbolInformation.
    /// Uses hardlinks if present, otherwise looks up symlinks in the symtab.
    fn scope_infos(&self, scope: &proto::Scope) -> Vec<proto::SymbolInformation> {
        if !scope.hardlinks.is_empty() {
            return scope.hardlinks.clone();
        }
        scope.symlinks.iter()
            .filter_map(|sym| self.symtab.get(sym.as_str()).map(|i| (*i).clone()))
            .collect()
    }
}

/// Determine the prefix string before a type in a definition.
fn prefix_before_tpe(info: &proto::SymbolInformation) -> &'static str {
    use proto::symbol_information::Kind;
    match Kind::try_from(info.kind) {
        Ok(Kind::Local) | Ok(Kind::Field) | Ok(Kind::Parameter)
        | Ok(Kind::SelfParameter) | Ok(Kind::UnknownKind) => ": ",
        _ => "",
    }
}

fn has_value(tpe: &proto::Type) -> bool {
    tpe.sealed_value.is_some()
}

fn is_nothing(tpe: &proto::Type) -> bool {
    matches!(
        &tpe.sealed_value,
        Some(proto::r#type::SealedValue::TypeRef(tr))
            if tr.symbol == "scala/Nothing#" && tr.type_arguments.is_empty() && !has_value_opt(&tr.prefix)
    )
}

fn is_any(tpe: &proto::Type) -> bool {
    matches!(
        &tpe.sealed_value,
        Some(proto::r#type::SealedValue::TypeRef(tr))
            if tr.symbol == "scala/Any#" && tr.type_arguments.is_empty() && !has_value_opt(&tr.prefix)
    )
}

fn is_object(tpe: &proto::Type) -> bool {
    matches!(
        &tpe.sealed_value,
        Some(proto::r#type::SealedValue::TypeRef(tr))
            if tr.symbol == "java/lang/Object#" && tr.type_arguments.is_empty() && !has_value_opt(&tr.prefix)
    )
}

fn has_value_opt(tpe: &Option<Box<proto::Type>>) -> bool {
    tpe.as_ref().is_some_and(|t| has_value(t))
}
