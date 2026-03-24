use crate::model::{ArchivedKodexIndex, ArchivedSymbol, ArchivedReferenceRole, ArchivedSymbolKind};
use crate::query::symbol::kind_str;
use super::{s, id};

/// Format a symbol as a one-line summary.
pub fn format_symbol_line(index: &ArchivedKodexIndex, sym: &ArchivedSymbol) -> String {
    let kind = kind_str(&sym.kind);
    let name = s(index, sym.name);
    let props = format_properties(sym.properties.into());
    let file = s(index, index.files[id(sym.file_id)].path);
    let member_of = format_member_of(index, sym);
    format!("  {kind} {name}{props}{member_of} ({file})")
}

/// Format a symbol with full detail (multi-line).
pub fn format_symbol_detail(index: &ArchivedKodexIndex, sym: &ArchivedSymbol, verbose: bool) -> String {
    let kind = kind_str(&sym.kind);
    let name = s(index, sym.name);
    let fqn = s(index, sym.fqn);
    let file = s(index, index.files[id(sym.file_id)].path);
    let sig = s(index, sym.type_signature);
    let props = format_properties(sym.properties.into());

    let mut out = format!("{kind} {name}{props}\n");
    out.push_str(&format!("  fqn: {fqn}\n"));
    out.push_str(&format!("  file: {file}\n"));
    if !sig.is_empty() {
        out.push_str(&format!("  signature: {sig}\n"));
    }
    // Parents (from stored parent FQNs on the symbol)
    if !sym.parents.is_empty() {
        let parent_fqns: Vec<&str> = sym.parents.iter()
            .map(|pid| s(index, *pid))
            .collect();
        out.push_str(&format!("  parents: {}\n", parent_fqns.join(", ")));
    }
    if verbose {
        let owner: u32 = sym.owner.into();
        if owner != u32::MAX {
            let owner_fqn = s(index, index.symbols[owner as usize].fqn);
            out.push_str(&format!("  owner: {owner_fqn}\n"));
        }
    }
    out
}

fn format_properties(props: u32) -> String {
    let mut names = Vec::new();
    if props & 0x4 != 0 { names.push("abstract"); }
    if props & 0x8 != 0 { names.push("final"); }
    if props & 0x10 != 0 { names.push("sealed"); }
    if props & 0x20 != 0 { names.push("implicit"); }
    if props & 0x40 != 0 { names.push("lazy"); }
    if props & 0x80 != 0 { names.push("case"); }
    if props & 0x400 != 0 { names.push("val"); }
    if props & 0x800 != 0 { names.push("var"); }
    if props & 0x4000 != 0 { names.push("enum"); }
    if props & 0x10000 != 0 { names.push("given"); }
    if props & 0x20000 != 0 { names.push("inline"); }
    if props & 0x40000 != 0 { names.push("open"); }
    if props & 0x200000 != 0 { names.push("opaque"); }
    if props & 0x400000 != 0 { names.push("override"); }
    if names.is_empty() {
        String::new()
    } else {
        format!(" [{}]", names.join(", "))
    }
}

fn format_member_of(index: &ArchivedKodexIndex, sym: &ArchivedSymbol) -> &'static str {
    if !matches!(sym.kind, ArchivedSymbolKind::Method | ArchivedSymbolKind::Field | ArchivedSymbolKind::Constructor) {
        return "";
    }
    let fqn = s(index, sym.fqn);
    let name = s(index, sym.name);
    if let Some(pos) = fqn.rfind(name) {
        if pos > 0 {
            match fqn.as_bytes()[pos - 1] {
                b'#' => return " [class/trait]",
                b'.' => return " [object]",
                _ => {}
            }
        }
    }
    ""
}

pub fn format_role(role: ArchivedReferenceRole) -> &'static str {
    match role {
        ArchivedReferenceRole::Definition => "<=",
        ArchivedReferenceRole::Reference => "=>",
        _ => "??",
    }
}
