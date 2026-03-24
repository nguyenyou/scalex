use crate::model::{ArchivedKodexIndex, ArchivedSymbolKind};
use crate::query::{s, id};
use crate::query::symbol::{resolve_one, edges_from, kind_str};
use crate::query::filter;

/// Composite command: complete picture of a type or method in one call.
/// Combines: signature + members + callers + callees + related types.
pub fn cmd_explore(index: &ArchivedKodexIndex, query: &str, limit: usize, kind_filter: Option<&str>, exclude: &[String]) {
    let sym = match resolve_one(index, query, kind_filter) {
        Some(s) => s,
        None => {
            println!("Not found: No symbol found matching '{query}'");
            return;
        }
    };

    let name = s(index, sym.name);
    let kind = kind_str(&sym.kind);
    let fqn = s(index, sym.fqn);
    let sig = s(index, sym.type_signature);
    let file_id: u32 = sym.file_id.into();
    let file = s(index, index.files[file_id as usize].path);
    let line: u32 = sym.line.into();
    let sym_id: u32 = sym.id.into();

    // Header
    println!("{kind} {name} — {file}:{}", line + 1);
    println!();

    // Signature
    if !sig.is_empty() {
        println!("  Signature: {sig}");
        println!();
    }

    // Parents
    if !sym.parents.is_empty() {
        let parent_names: Vec<String> = sym.parents.iter().map(|pid| {
            let pfqn = s(index, *pid);
            // Try to find display name in index
            index.symbols.iter()
                .find(|ps| s(index, ps.fqn) == pfqn)
                .map(|ps| s(index, ps.name).to_string())
                .unwrap_or_else(|| crate::symbol::symbol_display_name(pfqn).to_string())
        }).collect();
        println!("  Extends: {}", parent_names.join(", "));
    }

    // Members (for classes/traits/objects)
    if matches!(sym.kind,
        ArchivedSymbolKind::Class | ArchivedSymbolKind::Trait |
        ArchivedSymbolKind::Object | ArchivedSymbolKind::Interface
    ) {
        let members = edges_from(&index.members, sym_id);
        let filtered: Vec<u32> = members.into_iter()
            .filter(|&mid| {
                let m = &index.symbols[mid as usize];
                !matches!(m.kind, ArchivedSymbolKind::Parameter | ArchivedSymbolKind::TypeParameter | ArchivedSymbolKind::SelfParameter) &&
                !filter::is_plumbing(s(index, m.name)) &&
                !s(index, m.name).contains("$default$")
            })
            .collect();
        if !filtered.is_empty() {
            println!();
            println!("  Members ({}):", filtered.len());
            for &mid in filtered.iter().take(limit) {
                let m = &index.symbols[mid as usize];
                let mk = kind_str(&m.kind);
                let mn = s(index, m.name);
                let msig = s(index, m.type_signature);
                if !msig.is_empty() {
                    // For methods, show a compact signature
                    println!("    {mk} {mn}{msig}");
                } else {
                    println!("    {mk} {mn}");
                }
            }
            if filtered.len() > limit {
                println!("    ... and {} more", filtered.len() - limit);
            }
        }
    }

    // Subtypes / implementations (for traits/classes)
    if matches!(sym.kind, ArchivedSymbolKind::Trait | ArchivedSymbolKind::Class | ArchivedSymbolKind::Interface) {
        let subtypes = edges_from(&index.inheritance_forward, sym_id);
        let filtered: Vec<u32> = subtypes.into_iter()
            .filter(|&sid| {
                let st = &index.symbols[sid as usize];
                !matches!(st.kind, ArchivedSymbolKind::Local | ArchivedSymbolKind::Parameter) &&
                !filter::is_noise(index, st)
            })
            .collect();
        if !filtered.is_empty() {
            println!();
            println!("  Implementations ({}):", filtered.len());
            for &sid in filtered.iter().take(10) {
                let st = &index.symbols[sid as usize];
                let sk = kind_str(&st.kind);
                let sn = s(index, st.name);
                let sf_id: u32 = st.file_id.into();
                let sf = s(index, index.files[sf_id as usize].path);
                println!("    {sk} {sn} — {sf}");
            }
            if filtered.len() > 10 {
                println!("    ... and {} more", filtered.len() - 10);
            }
        }
    }

    // Callers (for methods/fields)
    if matches!(sym.kind, ArchivedSymbolKind::Method | ArchivedSymbolKind::Constructor | ArchivedSymbolKind::Field) {
        let mut caller_ids = edges_from(&index.call_graph_reverse, sym_id);
        // Trait-aware
        for base_fqn_id in sym.overridden_symbols.iter() {
            let base_fqn = s(index, *base_fqn_id);
            if let Some(base_sym) = index.symbols.iter().find(|bs| s(index, bs.fqn) == base_fqn) {
                let base_id: u32 = base_sym.id.into();
                caller_ids.extend(edges_from(&index.call_graph_reverse, base_id));
            }
        }
        caller_ids.sort_unstable();
        caller_ids.dedup();
        caller_ids.retain(|&cid| cid != sym_id);
        let filtered: Vec<u32> = caller_ids.into_iter()
            .filter(|&cid| {
                let sym = &index.symbols[cid as usize];
                !filter::is_callgraph_noise(index, sym) && !filter::matches_exclude(index, sym, exclude)
            })
            .collect();

        if !filtered.is_empty() {
            println!();
            println!("  Called by ({}):", filtered.len());
            for &cid in filtered.iter().take(10) {
                let c = &index.symbols[cid as usize];
                let cn = s(index, c.name);
                let cf_id: u32 = c.file_id.into();
                let cf = s(index, index.files[cf_id as usize].path);
                // Detect module
                let mod_id: u32 = index.files[cf_id as usize].module_id.into();
                let mod_tag = if mod_id != u32::MAX && !index.modules.is_empty() && (mod_id as usize) < index.modules.len() {
                    format!(" [{}]", s(index, index.modules[mod_id as usize].name))
                } else { String::new() };
                println!("    {cn}{mod_tag} — {cf}");
            }
            if filtered.len() > 10 {
                println!("    ... and {} more", filtered.len() - 10);
            }
        }
    }

    // Callees (for methods)
    if matches!(sym.kind, ArchivedSymbolKind::Method | ArchivedSymbolKind::Constructor) {
        let callees = edges_from(&index.call_graph_forward, sym_id);
        let filtered: Vec<u32> = callees.into_iter()
            .filter(|&cid| {
                let sym = &index.symbols[cid as usize];
                !filter::is_callgraph_noise(index, sym) && !filter::matches_exclude(index, sym, exclude)
            })
            .collect();

        if !filtered.is_empty() {
            println!();
            println!("  Service calls ({}):", filtered.len());
            for (i, &cid) in filtered.iter().take(20).enumerate() {
                let c = &index.symbols[cid as usize];
                let cn = s(index, c.name);
                let cf_id: u32 = c.file_id.into();
                // Detect if cross-module
                let callee_mod: u32 = index.files[cf_id as usize].module_id.into();
                let caller_mod: u32 = index.files[file_id as usize].module_id.into();
                let cross = if callee_mod != caller_mod && callee_mod != u32::MAX && caller_mod != u32::MAX {
                    let mod_name = if (callee_mod as usize) < index.modules.len() {
                        s(index, index.modules[callee_mod as usize].name)
                    } else { "?" };
                    format!(" [{mod_name}]")
                } else { String::new() };
                // Find owner name
                let owner_id: u32 = c.owner.into();
                let owner_name = if owner_id != u32::MAX && (owner_id as usize) < index.symbols.len() {
                    s(index, index.symbols[owner_id as usize].name)
                } else { "" };
                if !owner_name.is_empty() {
                    println!("    {}. {owner_name}.{cn}{cross}", i + 1);
                } else {
                    println!("    {}. {cn}{cross}", i + 1);
                }
            }
            if filtered.len() > 20 {
                println!("    ... and {} more", filtered.len() - 20);
            }
        }
    }
}
