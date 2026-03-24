use crate::model::{ArchivedKodexIndex, ArchivedSymbol, ArchivedSymbolKind};
use super::s;

/// Standard library / runtime prefixes excluded from call graphs and output.
const STDLIB_PREFIXES: &[&str] = &[
    "scala/", "java/lang/", "java/util/", "java/io/", "java/net/",
    "scala/collection/", "scala/runtime/", "scala/Predef",
];

/// Effect plumbing method names excluded from flow/callees output.
const PLUMBING_METHODS: &[&str] = &[
    "apply", "unapply", "toString", "hashCode", "equals", "copy",
    "map", "flatMap", "filter", "foreach", "collect", "foldLeft", "foldRight",
    "get", "getOrElse", "orElse", "isEmpty", "nonEmpty", "isDefined",
    "mkString", "productElement", "productPrefix", "canEqual",
    "productArity", "productIterator", "productElementName",
    "succeed", "pure", "attempt", "fromOption", "when", "unless",
    "traverse", "traverseOption", "traverseOptionUnit", "foreachDiscard",
    "validate", "parTraverseN",
];

/// Val property bitmask
const PROP_VAL: u32 = 0x400;
const PROP_VAR: u32 = 0x800;
const PROP_LAZY: u32 = 0x40;
const PROP_GIVEN: u32 = 0x10000;
const PROP_IMPLICIT: u32 = 0x20;

/// Check if a symbol FQN is from the standard library / runtime.
pub fn is_stdlib(fqn: &str) -> bool {
    STDLIB_PREFIXES.iter().any(|p| fqn.starts_with(p))
}

/// Check if a symbol is an effect plumbing method (apply, map, flatMap, etc.)
pub fn is_plumbing(name: &str) -> bool {
    PLUMBING_METHODS.contains(&name)
}

/// Check if a symbol is a val/var field accessor (reading a dependency, not a real call).
pub fn is_val_accessor(sym: &ArchivedSymbol) -> bool {
    let props: u32 = sym.properties.into();
    // val or var fields on classes/traits — these are dependency reads, not service calls
    (props & PROP_VAL != 0 || props & PROP_VAR != 0) &&
    matches!(sym.kind, ArchivedSymbolKind::Method | ArchivedSymbolKind::Field)
}

/// Check if a file is a test file (using pre-classified flag from index).
pub fn is_test_file(index: &ArchivedKodexIndex, file_id: u32) -> bool {
    index.files.get(file_id as usize).map_or(false, |f| f.is_test)
}

/// Check if a file is generated code (using pre-classified flag from index).
pub fn is_generated_file(index: &ArchivedKodexIndex, file_id: u32) -> bool {
    index.files.get(file_id as usize).map_or(false, |f| f.is_generated)
}

/// Check if a symbol should be excluded from default output.
/// Excludes: stdlib, test, generated, plumbing, case class synthetics.
pub fn is_noise(index: &ArchivedKodexIndex, sym: &ArchivedSymbol) -> bool {
    let fqn = s(index, sym.fqn);
    let name = s(index, sym.name);
    let file_id: u32 = sym.file_id.into();

    is_stdlib(fqn) ||
    is_test_file(index, file_id) ||
    is_generated_file(index, file_id) ||
    is_plumbing(name)
}

/// Check if a symbol is noise for call graph output (callers/callees/flow).
/// Filters: universal noise + val accessors + synthetics + user --exclude patterns.
pub fn is_callgraph_noise(index: &ArchivedKodexIndex, sym: &ArchivedSymbol) -> bool {
    if is_noise(index, sym) { return true; }
    let name = s(index, sym.name);
    // Default parameter accessors
    if name.contains("$default$") { return true; }
    // Tuple field accessors (_1, _2, ...)
    if name.len() >= 2 && name.starts_with('_') && name[1..].chars().all(|c| c.is_ascii_digit()) {
        return true;
    }
    // Val/var field reads — dependency wiring, not real calls
    if is_val_accessor(sym) { return true; }
    false
}

/// Check if a symbol matches any user-provided --exclude patterns.
/// Patterns match against both FQN and owner name (substring match).
pub fn matches_exclude(index: &ArchivedKodexIndex, sym: &ArchivedSymbol, exclude: &[String]) -> bool {
    if exclude.is_empty() { return false; }
    let fqn = s(index, sym.fqn);
    let name = s(index, sym.name);
    let owner_id: u32 = sym.owner.into();
    let owner_name = if owner_id != u32::MAX && (owner_id as usize) < index.symbols.len() {
        s(index, index.symbols[owner_id as usize].name)
    } else { "" };

    exclude.iter().any(|pattern| {
        fqn.contains(pattern.as_str()) ||
        name.contains(pattern.as_str()) ||
        owner_name.contains(pattern.as_str())
    })
}

/// Detect infrastructure "hub" symbols — high ref count, utility nature.
/// Returns (name, ref_count) pairs for the top N infrastructure symbols.
/// Used by `orient` to suggest --exclude patterns to the agent.
pub fn detect_infra_hubs(index: &ArchivedKodexIndex, top_n: usize) -> Vec<(String, usize)> {
    use rustc_hash::FxHashMap;

    // Count refs per symbol
    let mut ref_counts: FxHashMap<u32, usize> = FxHashMap::default();
    for rl in index.references.iter() {
        let sid: u32 = rl.symbol_id.into();
        ref_counts.insert(sid, rl.refs.len());
    }

    // Find objects/classes with very high ref counts that look like infrastructure
    let mut candidates: Vec<(String, usize)> = Vec::new();
    for (&sid, &count) in &ref_counts {
        if count < 200 { continue; } // only high-traffic symbols
        let sym = &index.symbols[sid as usize];
        if !matches!(sym.kind, ArchivedSymbolKind::Object | ArchivedSymbolKind::Class) { continue; }
        let file_id: u32 = sym.file_id.into();
        if is_test_file(index, file_id) || is_generated_file(index, file_id) { continue; }

        let name = s(index, sym.name);
        // Heuristic: infrastructure symbols have utility-like names
        let looks_infra = name.contains("Utils") || name.contains("Ops") ||
            name.contains("Operations") || name.contains("Helper") ||
            name.contains("IO") || name.contains("Factory") ||
            name.ends_with("Store") || name.ends_with("Database") ||
            name.ends_with("Mapping") || name.ends_with("Converter") ||
            name.ends_with("Provider") || name.ends_with("Enum") ||
            name.ends_with("Companion") || name.ends_with("Defaults");
        if looks_infra {
            candidates.push((name.to_string(), count));
        }
    }

    candidates.sort_by(|a, b| b.1.cmp(&a.1));
    candidates.truncate(top_n);
    candidates
}
