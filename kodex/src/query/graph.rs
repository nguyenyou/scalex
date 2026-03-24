use crate::model::{ArchivedKodexIndex, ArchivedSymbolKind};
use crate::query::symbol::{resolve_one, resolve_symbols, edges_from, kind_str, filter_by_kind};
use crate::query::format::format_symbol_line;
use super::{s, id};

pub fn cmd_callers(index: &ArchivedKodexIndex, query: &str, limit: usize, kind_filter: Option<&str>) {
    let matches = resolve_symbols(index, query);
    if matches.is_empty() {
        println!("Not found: No symbol found matching '{query}'");
        return;
    }
    let filtered = filter_by_kind(index, &matches, kind_filter);
    let candidates = if !filtered.is_empty() { filtered } else { matches };
    let name = s(index, candidates[0].name);

    let mut caller_ids: Vec<u32> = Vec::new();
    let target_ids: Vec<u32> = candidates.iter().map(|sym| sym.id.into()).collect();
    for sym in &candidates {
        let tid: u32 = sym.id.into();
        // Direct callers
        let direct = edges_from(&index.call_graph_reverse, tid);
        caller_ids.extend(direct);
        // Trait-aware: also get callers of overridden base symbols (trait methods)
        // When an impl overrides a trait method, callers often reference the trait's FQN
        for base_fqn_id in sym.overridden_symbols.iter() {
            let base_fqn = s(index, *base_fqn_id);
            // Find the base symbol in the index
            if let Some(base_sym) = index.symbols.iter().find(|bs| s(index, bs.fqn) == base_fqn) {
                let base_id: u32 = base_sym.id.into();
                let base_callers = edges_from(&index.call_graph_reverse, base_id);
                caller_ids.extend(base_callers);
            }
        }
    }
    caller_ids.sort_unstable();
    caller_ids.dedup();
    caller_ids.retain(|cid| !target_ids.contains(cid));

    let total = caller_ids.len();
    println!("{total} callers of '{name}'");
    for &cid in caller_ids.iter().take(limit) {
        println!("{}", format_symbol_line(index, &index.symbols[cid as usize]));
    }
    if total > limit {
        println!("... and {} more (use --limit 0 for all)", total - limit);
    }
}

pub fn cmd_callees(index: &ArchivedKodexIndex, query: &str, limit: usize, kind_filter: Option<&str>) {
    let sym = match resolve_one(index, query, kind_filter) {
        Some(s) => s,
        None => { println!("Not found: No symbol found matching '{query}'"); return; }
    };
    let name = s(index, sym.name);
    let sym_id: u32 = sym.id.into();
    let callees = edges_from(&index.call_graph_forward, sym_id);

    let total = callees.len();
    println!("{total} callees of '{name}'");
    for &cid in callees.iter().take(limit) {
        println!("{}", format_symbol_line(index, &index.symbols[cid as usize]));
    }
    if total > limit {
        println!("... and {} more (use --limit 0 for all)", total - limit);
    }
}

pub fn cmd_hierarchy(index: &ArchivedKodexIndex, query: &str, kind_filter: Option<&str>) {
    let sym = match resolve_one(index, query, kind_filter) {
        Some(s) => s,
        None => { println!("Not found: No symbol found matching '{query}'"); return; }
    };
    let name = s(index, sym.name);
    let kind = kind_str(&sym.kind);
    let sym_id: u32 = sym.id.into();

    // Use sdbex-style output: show supertypes and subtypes as a tree
    println!("{kind} {name}");

    // Supertypes: walk parent FQNs stored on the symbol
    if !sym.parents.is_empty() {
        print_supertypes_from_fqns(index, &sym.parents, 1, &mut rustc_hash::FxHashSet::default());
    }

    // Subtypes
    let children = edges_from(&index.inheritance_forward, sym_id);
    if !children.is_empty() {
        print_subtypes(index, sym_id, 1, &mut rustc_hash::FxHashSet::default(), 50);
    }
}

/// Print supertypes from stored parent FQNs. Shows [external] for symbols not in index.
fn print_supertypes_from_fqns(index: &ArchivedKodexIndex, parent_fqns: &[rkyv::rend::u32_le], indent: usize, visited: &mut rustc_hash::FxHashSet<String>) {
    for pfqn_id in parent_fqns {
        let pfqn = s(index, *pfqn_id);
        if !visited.insert(pfqn.to_string()) { continue; }
        let prefix = "  ".repeat(indent);
        // Try to find this parent in the index
        let found = index.symbols.iter().find(|sym| s(index, sym.fqn) == pfqn);
        match found {
            Some(psym) => {
                let kind = kind_str(&psym.kind);
                let name = s(index, psym.name);
                println!("{prefix}{kind} {name} ({pfqn})");
                // Recurse into this parent's parents
                if !psym.parents.is_empty() {
                    print_supertypes_from_fqns(index, &psym.parents, indent + 1, visited);
                }
            }
            None => {
                println!("{prefix}[external] {pfqn}");
            }
        }
    }
}

fn print_subtypes(index: &ArchivedKodexIndex, sym_id: u32, indent: usize, visited: &mut rustc_hash::FxHashSet<u32>, limit: usize) {
    if !visited.insert(sym_id) { return; }
    let children = edges_from(&index.inheritance_forward, sym_id);
    for cid in children {
        if visited.len() > limit { return; }
        let sym = &index.symbols[cid as usize];
        if matches!(sym.kind, ArchivedSymbolKind::Local | ArchivedSymbolKind::Parameter) { continue; }
        let kind = kind_str(&sym.kind);
        let name = s(index, sym.name);
        let file = s(index, index.files[id(sym.file_id)].path);
        let prefix = "  ".repeat(indent);
        println!("{prefix}{kind} {name} ({file})");
        print_subtypes(index, cid, indent + 1, visited, limit);
    }
}

pub fn cmd_members(index: &ArchivedKodexIndex, query: &str, limit: usize, kind_filter: Option<&str>) {
    let sym = match resolve_one(index, query, None) {
        Some(s) => s,
        None => { println!("Not found: No symbol found matching '{query}'"); return; }
    };
    let name = s(index, sym.name);
    let sym_id: u32 = sym.id.into();
    let members = edges_from(&index.members, sym_id);

    let filtered: Vec<u32> = members.into_iter()
        .filter(|&mid| {
            let m = &index.symbols[mid as usize];
            !matches!(m.kind, ArchivedSymbolKind::Parameter | ArchivedSymbolKind::TypeParameter | ArchivedSymbolKind::SelfParameter)
        })
        .collect();

    let after_kind: Vec<u32> = if let Some(k) = kind_filter {
        let k_lower = k.to_lowercase();
        filtered.into_iter().filter(|&mid| {
            kind_str(&index.symbols[mid as usize].kind) == k_lower
        }).collect()
    } else {
        filtered
    };

    let total = after_kind.len();
    println!("{total} members of '{name}'");
    for &mid in after_kind.iter().take(limit) {
        println!("{}", format_symbol_line(index, &index.symbols[mid as usize]));
    }
    if total > limit {
        println!("... and {} more (use --limit 0 for all)", total - limit);
    }
}

pub fn cmd_overrides(index: &ArchivedKodexIndex, query: &str, kind_filter: Option<&str>) {
    let sym = match resolve_one(index, query, kind_filter) {
        Some(s) => s,
        None => { println!("Not found: No symbol found matching '{query}'"); return; }
    };
    let name = s(index, sym.name);
    let kind = kind_str(&sym.kind);
    let sym_id: u32 = sym.id.into();

    let overriders = edges_from(&index.overrides, sym_id);

    println!("Overrides of '{name}'");
    println!("  {kind} {name}");
    if overriders.is_empty() {
        println!("  (no overriders found)");
    } else {
        for oid in overriders {
            println!("{}", format_symbol_line(index, &index.symbols[oid as usize]));
        }
    }
}
