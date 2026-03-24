use crate::model::{ArchivedKodexIndex, ArchivedSymbolKind};
use crate::query::{s, id};
use crate::query::symbol::{resolve_one, edges_from, kind_str};
use crate::query::filter;

/// What breaks if this symbol changes: callers, overrides, test coverage, affected modules.
pub fn cmd_impact(index: &ArchivedKodexIndex, query: &str, kind_filter: Option<&str>, exclude: &[String]) {
    let sym = match resolve_one(index, query, kind_filter) {
        Some(s) => s,
        None => { println!("Not found: No symbol found matching '{query}'"); return; }
    };

    let name = s(index, sym.name);
    let kind = kind_str(&sym.kind);
    let fqn = s(index, sym.fqn);
    let sym_id: u32 = sym.id.into();

    println!("{kind} {name} ({fqn})");
    println!();

    // Direct callers (filtered)
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
    let production_callers: Vec<u32> = caller_ids.iter().copied()
        .filter(|&cid| {
            let sym = &index.symbols[cid as usize];
            !filter::is_noise(index, sym) && !filter::matches_exclude(index, sym, exclude)
        })
        .collect();

    println!("Direct callers ({}):", production_callers.len());
    for &cid in production_callers.iter().take(15) {
        let c = &index.symbols[cid as usize];
        let cn = s(index, c.name);
        let cf_id: u32 = c.file_id.into();
        let cf = s(index, index.files[cf_id as usize].path);
        let cl: u32 = c.line.into();
        println!("  {cn} — {cf}:{}", cl + 1);
    }
    if production_callers.len() > 15 {
        println!("  ... and {} more", production_callers.len() - 15);
    }
    println!();

    // Overrides
    let overriders = edges_from(&index.overrides, sym_id);
    if !overriders.is_empty() {
        println!("Overrides ({}):", overriders.len());
        for &oid in overriders.iter().take(10) {
            let o = &index.symbols[oid as usize];
            let on = s(index, o.name);
            let of_id: u32 = o.file_id.into();
            let of_path = s(index, index.files[of_id as usize].path);
            println!("  {on} — {of_path}");
        }
        println!();
    }

    // Test coverage: count test files that reference this symbol
    let mut test_count = 0;
    let mut test_files: Vec<&str> = Vec::new();
    for rl in index.references.iter() {
        let sid: u32 = rl.symbol_id.into();
        if sid != sym_id { continue; }
        for r in rl.refs.iter() {
            let fid: u32 = r.file_id.into();
            if filter::is_test_file(index, fid) {
                let fp = s(index, index.files[fid as usize].path);
                if !test_files.contains(&fp) {
                    test_files.push(fp);
                    test_count += 1;
                }
            }
        }
    }
    println!("Test coverage: {} test file(s)", test_count);
    for tf in test_files.iter().take(5) {
        println!("  {tf}");
    }
    if test_files.len() > 5 {
        println!("  ... and {} more", test_files.len() - 5);
    }
    println!();

    // Affected modules
    let mut affected: rustc_hash::FxHashSet<u32> = rustc_hash::FxHashSet::default();
    for &cid in &production_callers {
        let cf_id: u32 = index.symbols[cid as usize].file_id.into();
        let mid: u32 = index.files[cf_id as usize].module_id.into();
        if mid != u32::MAX { affected.insert(mid); }
    }
    if !affected.is_empty() {
        let names: Vec<&str> = affected.iter()
            .filter_map(|&mid| index.modules.get(mid as usize).map(|m| s(index, m.name)))
            .collect();
        println!("Modules affected: {}", names.join(", "));
    }
}
