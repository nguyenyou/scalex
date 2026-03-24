use rustc_hash::FxHashMap;
use crate::model::{ArchivedKodexIndex, ArchivedSymbolKind};
use crate::query::{s, id};
use crate::query::symbol::kind_str;
use crate::query::filter;

/// Project overview: modules, hub types, entry points.
pub fn cmd_orient(index: &ArchivedKodexIndex) {
    let total_files = index.files.len();
    let total_symbols = index.symbols.len();
    let test_files = index.files.iter().filter(|f| f.is_test).count();
    let gen_files = index.files.iter().filter(|f| f.is_generated).count();
    let src_files = total_files - test_files - gen_files;

    println!("Project overview");
    println!();
    println!("Files: {} source, {} test, {} generated ({} total)",
        src_files, test_files, gen_files, total_files);
    println!("Symbols: {}", total_symbols);
    println!();

    // Modules
    if !index.modules.is_empty() {
        println!("Modules ({}):", index.modules.len());
        // Collect stats per module
        let mut mod_stats: Vec<(String, u32, u32)> = Vec::new(); // (name, files, symbols)
        for m in index.modules.iter() {
            let name = s(index, m.name).to_string();
            let fc: u32 = m.file_count.into();
            // Count symbols in this module
            let mod_id = index.modules.iter().position(|mm| s(index, mm.name) == name).unwrap_or(0) as u32;
            let sym_count = index.symbols.iter()
                .filter(|sym| {
                    let fid: u32 = sym.file_id.into();
                    fid < index.files.len() as u32 && {
                        let mid: u32 = index.files[fid as usize].module_id.into();
                        mid == mod_id
                    }
                })
                .count() as u32;
            mod_stats.push((name, fc, sym_count));
        }
        mod_stats.sort_by(|a, b| b.2.cmp(&a.2)); // sort by symbol count desc
        for (name, files, syms) in mod_stats.iter().take(20) {
            println!("  {:<25} — {:>4} files, {:>6} symbols", name, files, syms);
        }
        if mod_stats.len() > 20 {
            println!("  ... and {} more modules", mod_stats.len() - 20);
        }
        println!();
    }

    // Hub types: most-referenced symbols (non-test, non-generated, non-stdlib)
    println!("Hub types (most referenced):");
    let mut ref_counts: FxHashMap<u32, usize> = FxHashMap::default();
    for rl in index.references.iter() {
        let sid: u32 = rl.symbol_id.into();
        let count = rl.refs.len();
        ref_counts.insert(sid, count);
    }

    let mut hubs: Vec<(u32, usize)> = ref_counts.into_iter()
        .filter(|&(sid, _)| {
            let sym = &index.symbols[sid as usize];
            // Only classes, traits, objects
            matches!(sym.kind, ArchivedSymbolKind::Class | ArchivedSymbolKind::Trait | ArchivedSymbolKind::Object) &&
            !filter::is_noise(index, sym)
        })
        .collect();
    hubs.sort_by(|a, b| b.1.cmp(&a.1));

    for &(sid, count) in hubs.iter().take(15) {
        let sym = &index.symbols[sid as usize];
        let kind = kind_str(&sym.kind);
        let name = s(index, sym.name);
        let fqn = s(index, sym.fqn);
        // Count implementations (subtypes)
        let impls = crate::query::symbol::edges_from(&index.inheritance_forward, sid).len();
        let impls_str = if impls > 0 { format!(", {} impls", impls) } else { String::new() };
        println!("  {:<5} {:<30} — {:>4} refs{} ({})", kind, name, count, impls_str, fqn);
    }
    println!();

    // Entry points: @main defs, objects extending *Server/*App
    println!("Entry points:");
    let mut entry_count = 0;
    for sym in index.symbols.iter() {
        let name = s(index, sym.name);
        let fqn = s(index, sym.fqn);
        let file_id: u32 = sym.file_id.into();
        if filter::is_test_file(index, file_id) { continue; }
        if filter::is_generated_file(index, file_id) { continue; }

        let is_entry = matches!(sym.kind, ArchivedSymbolKind::Object) && (
            name.ends_with("Server") || name.ends_with("App") || name.ends_with("Main")
        );
        if is_entry {
            let file = s(index, index.files[file_id as usize].path);
            println!("  object {} — {}:{}", name, file, u32::from(sym.line) + 1);
            entry_count += 1;
            if entry_count >= 15 { println!("  ... and more"); break; }
        }
    }
    if entry_count == 0 {
        println!("  (none detected)");
    }
    println!();

    // Infrastructure noise detection — suggest --exclude patterns
    let infra_hubs = filter::detect_infra_hubs(index, 15);
    if !infra_hubs.is_empty() {
        println!("Infrastructure (high-traffic utility symbols):");
        for (name, count) in &infra_hubs {
            println!("  {:<35} — {:>5} refs", name, count);
        }
        // Build suggested --exclude string from the top names
        let exclude_names: Vec<&str> = infra_hubs.iter()
            .take(8)
            .map(|(n, _)| n.as_str())
            .collect();
        println!();
        println!("Suggested --exclude for deep exploration:");
        println!("  --exclude \"{}\"", exclude_names.join(","));
    }
}
