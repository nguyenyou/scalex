use crate::model::{ArchivedKodexIndex, ArchivedReferenceRole};
use crate::query::symbol::resolve_symbols;
use crate::query::format::format_role;
use super::{s, id};

pub fn cmd_refs(index: &ArchivedKodexIndex, query: &str, limit: usize) {
    let matches = resolve_symbols(index, query);
    if matches.is_empty() {
        println!("Not found: No symbol found matching '{query}'");
        return;
    }

    let fqns: Vec<&str> = matches.iter().map(|sym| s(index, sym.fqn)).collect();
    let name = s(index, matches[0].name);

    // Collect: (line, col, is_def, file)
    let mut occs: Vec<(u32, u32, bool, &str)> = Vec::new();
    for rl in index.references.iter() {
        let sym_id: u32 = rl.symbol_id.into();
        let sym_fqn = s(index, index.symbols[sym_id as usize].fqn);
        if fqns.contains(&sym_fqn) {
            for r in rl.refs.iter() {
                let file_id: u32 = r.file_id.into();
                let file = s(index, index.files[file_id as usize].path);
                let line: u32 = r.line.into();
                let col: u32 = r.col.into();
                let is_def = matches!(r.role, ArchivedReferenceRole::Definition);
                occs.push((line, col, is_def, file));
            }
        }
    }

    occs.sort_by(|a, b| a.3.cmp(b.3).then(a.0.cmp(&b.0)).then(a.1.cmp(&b.1)));
    let total = occs.len();
    println!("{total} occurrences of '{name}'");
    for (line, col, is_def, file) in occs.iter().take(limit) {
        let role_str = if *is_def { "<=" } else { "=>" };
        println!("  [{}:{}] {role_str}", line + 1, col + 1);
        println!("    {file}:{}", line + 1);
    }
    if total > limit {
        println!("... and {} more", total - limit);
    }
}
