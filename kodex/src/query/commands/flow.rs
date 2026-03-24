use crate::model::{ArchivedKodexIndex, ArchivedSymbolKind};
use crate::query::{s, id};
use crate::query::symbol::{resolve_one, edges_from, kind_str};
use crate::query::filter;

/// Downstream call tree with module boundary annotations.
pub fn cmd_flow(index: &ArchivedKodexIndex, query: &str, depth: usize, kind_filter: Option<&str>, exclude: &[String]) {
    let sym = match resolve_one(index, query, kind_filter) {
        Some(s) => s,
        None => { println!("Not found: No symbol found matching '{query}'"); return; }
    };

    let name = s(index, sym.name);
    let sym_id: u32 = sym.id.into();
    let file_id: u32 = sym.file_id.into();
    let caller_mod: u32 = index.files[file_id as usize].module_id.into();
    let mod_tag = module_tag(index, caller_mod);

    println!("{name}{mod_tag}");
    let mut visited = rustc_hash::FxHashSet::default();
    visited.insert(sym_id);
    print_flow_tree(index, sym_id, 1, depth, caller_mod, &mut visited, exclude);
}

fn print_flow_tree(
    index: &ArchivedKodexIndex,
    sym_id: u32,
    indent: usize,
    max_depth: usize,
    root_mod: u32,
    visited: &mut rustc_hash::FxHashSet<u32>,
    exclude: &[String],
) {
    if indent > max_depth { return; }
    let callees = edges_from(&index.call_graph_forward, sym_id);
    let filtered: Vec<u32> = callees.into_iter()
        .filter(|&cid| {
            let sym = &index.symbols[cid as usize];
            !filter::is_callgraph_noise(index, sym) && !filter::matches_exclude(index, sym, exclude)
        })
        .collect();

    for (i, &cid) in filtered.iter().enumerate() {
        let c = &index.symbols[cid as usize];
        let cn = s(index, c.name);
        let cf_id: u32 = c.file_id.into();
        let callee_mod: u32 = index.files[cf_id as usize].module_id.into();

        let cross = if callee_mod != root_mod && callee_mod != u32::MAX && root_mod != u32::MAX {
            format!("{} — cross-module", module_tag(index, callee_mod))
        } else {
            module_tag(index, callee_mod)
        };

        // Owner for context
        let owner_id: u32 = c.owner.into();
        let owner = if owner_id != u32::MAX && (owner_id as usize) < index.symbols.len() {
            format!("{}.", s(index, index.symbols[owner_id as usize].name))
        } else { String::new() };

        let is_last = i == filtered.len() - 1;
        let prefix = "│   ".repeat(indent.saturating_sub(1));
        let branch = if is_last { "└── " } else { "├── " };
        println!("{prefix}{branch}{owner}{cn}{cross}");

        if visited.insert(cid) {
            print_flow_tree(index, cid, indent + 1, max_depth, root_mod, visited, exclude);
        }
    }
}

fn module_tag(index: &ArchivedKodexIndex, mod_id: u32) -> String {
    if mod_id == u32::MAX || index.modules.is_empty() || mod_id as usize >= index.modules.len() {
        return String::new();
    }
    format!(" [{}]", s(index, index.modules[mod_id as usize].name))
}
