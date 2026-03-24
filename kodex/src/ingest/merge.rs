use rustc_hash::FxHashMap;

use crate::model::*;

/// Build a KodexIndex from intermediate documents.
pub fn build_index(docs: Vec<IntermediateDoc>) -> KodexIndex {
    let mut string_set: FxHashMap<String, u32> = FxHashMap::default();
    let mut strings_vec: Vec<String> = Vec::new();

    let mut intern = |s: &str| -> u32 {
        if let Some(&id) = string_set.get(s) {
            return id;
        }
        let id = strings_vec.len() as u32;
        strings_vec.push(s.to_string());
        string_set.insert(s.to_string(), id);
        id
    };

    // Phase 1: Collect all files, classify, detect modules, assign file IDs
    let mut file_map: FxHashMap<String, u32> = FxHashMap::default();
    let mut files: Vec<FileEntry> = Vec::new();
    let mut module_map: FxHashMap<String, u32> = FxHashMap::default();
    let mut modules: Vec<MillModule> = Vec::new();

    for doc in &docs {
        if !file_map.contains_key(&doc.uri) {
            let fid = files.len() as u32;
            file_map.insert(doc.uri.clone(), fid);
            let uri = &doc.uri;
            let module_id = detect_module(uri, &mut module_map, &mut modules, &mut intern);
            files.push(FileEntry {
                path: intern(uri),
                module_id,
                is_test: classify_test(uri),
                is_generated: classify_generated(uri),
            });
        }
    }

    // Phase 2: Collect all symbols, assign symbol IDs, intern strings
    let mut sym_map: FxHashMap<String, u32> = FxHashMap::default();
    let mut symbols: Vec<Symbol> = Vec::new();
    let mut parent_fqns: Vec<Vec<String>> = Vec::new();
    let mut override_fqns: Vec<Vec<String>> = Vec::new();

    for doc in &docs {
        let file_id = file_map[&doc.uri];
        for isym in &doc.symbols {
            if sym_map.contains_key(&isym.fqn) {
                continue; // dedup across documents
            }
            let sid = symbols.len() as u32;
            sym_map.insert(isym.fqn.clone(), sid);

            // Find definition location from occurrences in same doc
            let (line, col) = doc.occurrences.iter()
                .find(|o| o.symbol == isym.fqn && matches!(o.role, ReferenceRole::Definition))
                .map(|o| (o.start_line, o.start_col))
                .unwrap_or((0, 0));

            let parent_string_ids: Vec<u32> = isym.parents.iter().map(|p| intern(p)).collect();
            let override_string_ids: Vec<u32> = isym.overridden_symbols.iter().map(|o| intern(o)).collect();
            symbols.push(Symbol {
                id: sid,
                name: intern(&isym.display_name),
                fqn: intern(&isym.fqn),
                kind: isym.kind,
                file_id,
                line,
                col,
                type_signature: intern(&isym.signature),
                owner: u32::MAX, // resolved in phase 3
                properties: isym.properties,
                access: isym.access,
                parents: parent_string_ids,
                overridden_symbols: override_string_ids,
            });
            parent_fqns.push(isym.parents.clone());
            override_fqns.push(isym.overridden_symbols.clone());
        }
    }

    // Phase 3: Resolve owner symbol IDs
    for sym in &mut symbols {
        let fqn_str = &strings_vec[sym.fqn as usize];
        let owner_fqn = crate::symbol::symbol_owner(fqn_str);
        if let Some(&owner_id) = sym_map.get(owner_fqn) {
            sym.owner = owner_id;
        }
    }

    // Phase 4: Build references index
    let mut refs_by_sym: FxHashMap<u32, Vec<Reference>> = FxHashMap::default();
    for doc in &docs {
        let file_id = file_map[&doc.uri];
        for occ in &doc.occurrences {
            if let Some(&sid) = sym_map.get(&occ.symbol) {
                refs_by_sym.entry(sid).or_default().push(Reference {
                    file_id,
                    line: occ.start_line,
                    col: occ.start_col,
                    role: occ.role,
                });
            }
        }
    }
    let references: Vec<ReferenceList> = refs_by_sym.into_iter()
        .map(|(symbol_id, refs)| ReferenceList { symbol_id, refs })
        .collect();

    // Phase 5: Build inheritance indexes
    let mut inh_fwd: FxHashMap<u32, Vec<u32>> = FxHashMap::default();
    let mut inh_rev: FxHashMap<u32, Vec<u32>> = FxHashMap::default();
    for (i, parents) in parent_fqns.iter().enumerate() {
        let child_id = i as u32;
        for parent_fqn in parents {
            if let Some(&parent_id) = sym_map.get(parent_fqn) {
                inh_fwd.entry(parent_id).or_default().push(child_id);
                inh_rev.entry(child_id).or_default().push(parent_id);
            }
        }
    }

    // Phase 6: Build members index (owner → members)
    let mut members_map: FxHashMap<u32, Vec<u32>> = FxHashMap::default();
    for sym in &symbols {
        if sym.owner != u32::MAX {
            members_map.entry(sym.owner).or_default().push(sym.id);
        }
    }

    // Phase 7: Build overrides index
    let mut overrides_map: FxHashMap<u32, Vec<u32>> = FxHashMap::default();
    for (i, overrides) in override_fqns.iter().enumerate() {
        let overrider_id = i as u32;
        for base_fqn in overrides {
            if let Some(&base_id) = sym_map.get(base_fqn) {
                overrides_map.entry(base_id).or_default().push(overrider_id);
            }
        }
    }

    // Phase 8: Build call graph from occurrences
    // For each method/constructor/field definition, find references within its body range.
    // Body range: from the def's line to the next sibling def with the same owner.
    // This matches sdbex's findEnclosingSymbol / findCallees heuristic.
    let mut call_fwd: FxHashMap<u32, Vec<u32>> = FxHashMap::default();
    let mut call_rev: FxHashMap<u32, Vec<u32>> = FxHashMap::default();

    // Group occurrences by file URI
    let mut occs_by_file: FxHashMap<&str, Vec<&IntermediateOccurrence>> = FxHashMap::default();
    for doc in &docs {
        for occ in &doc.occurrences {
            occs_by_file.entry(&doc.uri).or_default().push(occ);
        }
    }

    for (_file, occs) in &occs_by_file {
        // Collect all definition occurrences for methods/constructors/fields
        struct DefInfo { sid: u32, owner: u32, start_line: u32, end_col: u32, body_end: u32 }
        let mut defs: Vec<DefInfo> = Vec::new();
        for occ in occs.iter() {
            if !matches!(occ.role, ReferenceRole::Definition) { continue; }
            // Skip local definitions (they're inside method bodies, not sibling boundaries)
            if occ.symbol.starts_with("local") { continue; }
            let Some(&sid) = sym_map.get(&occ.symbol) else { continue; };
            if sid >= symbols.len() as u32 { continue; }
            let kind = symbols[sid as usize].kind;
            if !matches!(kind, SymbolKind::Method | SymbolKind::Constructor | SymbolKind::Field) { continue; }
            defs.push(DefInfo {
                sid,
                owner: symbols[sid as usize].owner,
                start_line: occ.start_line,
                end_col: occ.end_col,
                body_end: u32::MAX,
            });
        }
        defs.sort_by_key(|d| d.start_line);

        // Compute body_end for each def: next sibling (same owner) def's start line
        for i in 0..defs.len() {
            let owner = defs[i].owner;
            let start = defs[i].start_line;
            // Find next def with same owner after this one
            for j in (i + 1)..defs.len() {
                if defs[j].start_line > start && defs[j].owner == owner {
                    defs[i].body_end = defs[j].start_line;
                    break;
                }
            }
        }

        // For each reference, find enclosing definition and record caller→callee edge
        for occ in occs.iter() {
            if !matches!(occ.role, ReferenceRole::Reference) { continue; }
            let Some(&callee_id) = sym_map.get(&occ.symbol) else { continue; };
            if callee_id >= symbols.len() as u32 { continue; }
            let callee_kind = symbols[callee_id as usize].kind;
            if !matches!(callee_kind, SymbolKind::Method | SymbolKind::Constructor | SymbolKind::Field) { continue; }

            let line = occ.start_line;
            let col = occ.start_col;
            // Find the closest enclosing def: last def whose start_line <= line && line < body_end
            // On the def line itself, only count refs after the def's end column (single-line body)
            let enclosing = defs.iter().rev().find(|d| {
                d.start_line <= line && line < d.body_end &&
                (line > d.start_line || col > d.end_col)
            });
            if let Some(def) = enclosing {
                if def.sid != callee_id {
                    call_fwd.entry(def.sid).or_default().push(callee_id);
                    call_rev.entry(callee_id).or_default().push(def.sid);
                }
            }
        }
    }

    // Deduplicate edge lists
    fn dedup_edges(map: FxHashMap<u32, Vec<u32>>) -> Vec<EdgeList> {
        map.into_iter().map(|(from, mut to)| {
            to.sort_unstable();
            to.dedup();
            EdgeList { from, to }
        }).collect()
    }

    fn to_edge_lists(map: FxHashMap<u32, Vec<u32>>) -> Vec<EdgeList> {
        let mut edges = dedup_edges(map);
        edges.sort_by_key(|e| e.from); // sort by `from` for binary search at query time
        edges
    }

    // Sort the string table and rewrite all IDs
    // (Skip for now — strings are insertion-ordered. Sort can be added later for
    // binary search optimization at query time.)

    // Phase 9: Build trigram index + name hash index for fast symbol lookup
    let (name_trigrams, name_hash_buckets, name_hash_size) = build_name_indexes(&symbols, &strings_vec);

    KodexIndex {
        version: KODEX_INDEX_VERSION,
        strings: strings_vec,
        files,
        symbols,
        references,
        call_graph_forward: to_edge_lists(call_fwd),
        call_graph_reverse: to_edge_lists(call_rev),
        inheritance_forward: to_edge_lists(inh_fwd),
        inheritance_reverse: to_edge_lists(inh_rev),
        members: to_edge_lists(members_map),
        overrides: to_edge_lists(overrides_map),
        modules,
        name_trigrams,
        name_hash_buckets,
        name_hash_size,
    }
}

// ── Trigram + hash index building ───────────────────────────────────────────

/// Pack 3 lowercase ASCII bytes into a u32 key.
fn trigram_key(a: u8, b: u8, c: u8) -> u32 {
    (a.to_ascii_lowercase() as u32)
        | ((b.to_ascii_lowercase() as u32) << 8)
        | ((c.to_ascii_lowercase() as u32) << 16)
}

/// Extract all trigram keys from a string (lowercased).
fn extract_trigrams(s: &str) -> Vec<u32> {
    let bytes = s.as_bytes();
    if bytes.len() < 3 { return vec![]; }
    let mut trigrams = Vec::with_capacity(bytes.len() - 2);
    for i in 0..bytes.len() - 2 {
        trigrams.push(trigram_key(bytes[i], bytes[i + 1], bytes[i + 2]));
    }
    trigrams.sort_unstable();
    trigrams.dedup();
    trigrams
}

/// Simple string hash for name bucketing.
fn name_hash(s: &str, bucket_count: u32) -> u32 {
    let mut h: u32 = 0;
    for b in s.as_bytes() {
        h = h.wrapping_mul(31).wrapping_add(b.to_ascii_lowercase() as u32);
    }
    h % bucket_count
}

fn build_name_indexes(symbols: &[Symbol], strings: &[String]) -> (Vec<TrigramEntry>, Vec<HashBucket>, u32) {
    // Trigram index: trigram_key → Vec<symbol_id>
    let mut tri_map: FxHashMap<u32, Vec<u32>> = FxHashMap::default();
    // Hash index: bucket → Vec<symbol_id>
    let bucket_count = ((symbols.len() / 4).max(1024)) as u32;
    let mut hash_map: FxHashMap<u32, Vec<u32>> = FxHashMap::default();

    for sym in symbols {
        let name = &strings[sym.name as usize];
        let fqn = &strings[sym.fqn as usize];
        let sid = sym.id;

        // Trigram index: index both display name and FQN
        for tri in extract_trigrams(name) {
            tri_map.entry(tri).or_default().push(sid);
        }
        // Also index the last segment of FQN for suffix matching
        if let Some(last_seg) = fqn.rsplit('/').next() {
            for tri in extract_trigrams(last_seg) {
                tri_map.entry(tri).or_default().push(sid);
            }
        }

        // Hash index on display name
        let bucket = name_hash(name, bucket_count);
        hash_map.entry(bucket).or_default().push(sid);
    }

    // Convert to sorted Vec<TrigramEntry>
    let mut trigrams: Vec<TrigramEntry> = tri_map.into_iter()
        .map(|(key, mut ids)| {
            ids.sort_unstable();
            ids.dedup();
            TrigramEntry { key, symbol_ids: ids }
        })
        .collect();
    trigrams.sort_by_key(|t| t.key);

    // Convert hash buckets
    let mut buckets: Vec<HashBucket> = (0..bucket_count)
        .map(|i| {
            let ids = hash_map.remove(&i).unwrap_or_default();
            HashBucket { symbol_ids: ids }
        })
        .collect();

    (trigrams, buckets, bucket_count)
}

// ── File classification ─────────────────────────────────────────────────────

fn classify_test(uri: &str) -> bool {
    let lower = uri.to_lowercase();
    lower.contains("/test/") || lower.contains("/tests/") ||
    lower.contains("/it/") || lower.contains("/spec/") ||
    lower.ends_with("test.scala") || lower.ends_with("spec.scala") ||
    lower.ends_with("suite.scala") || lower.ends_with("integ.scala")
}

fn classify_generated(uri: &str) -> bool {
    let lower = uri.to_lowercase();
    lower.contains("compilescalapb.dest") || lower.contains("compilepb.dest") ||
    lower.contains("/generated/") || lower.contains("/src_managed/") ||
    lower.contains("generatedsources") ||
    lower.ends_with(".pb.scala") || lower.ends_with("grpc.scala") ||
    lower.contains("buildinfo.scala")
}

// ── Mill module detection ───────────────────────────────────────────────────

/// Detect the Mill module a file belongs to from its URI path.
/// Uses the first meaningful path segment(s). Examples:
///   "modules/billing/billing/jvm/src/..." → "billing"
///   "platform/database/src/..." → "database"
///   "services/admin/jvm/src/..." → "admin"
fn detect_module(
    uri: &str,
    module_map: &mut FxHashMap<String, u32>,
    modules: &mut Vec<MillModule>,
    intern: &mut impl FnMut(&str) -> u32,
) -> u32 {
    let module_name = extract_module_name(uri);
    if module_name.is_empty() {
        return u32::MAX;
    }
    if let Some(&id) = module_map.get(&module_name) {
        modules[id as usize].file_count += 1;
        return id;
    }
    let id = modules.len() as u32;
    module_map.insert(module_name.clone(), id);
    modules.push(MillModule {
        name: intern(&module_name),
        source_paths: vec![],
        file_count: 1,
        symbol_count: 0,
    });
    id
}

fn extract_module_name(uri: &str) -> String {
    let parts: Vec<&str> = uri.split('/').collect();
    if parts.len() < 2 { return String::new(); }

    // Skip common prefixes to find the module name
    // "modules/X/..." → X, "platform/X/..." → X, "services/X/..." → X
    // For "out/modules/X/..." paths, skip "out" first
    let start = if parts[0] == "out" { 1 } else { 0 };
    if start >= parts.len() { return String::new(); }

    // The module name is typically the second meaningful segment
    // "modules/billing/billing/jvm/src" → "billing"
    // "platform/database/src" → "database"
    let first = parts[start];
    if start + 1 < parts.len() {
        let second = parts[start + 1];
        // Stop at src/test/jvm/js/shared markers
        if matches!(second, "src" | "test" | "tests" | "jvm" | "js" | "shared" | "native") {
            return first.to_string();
        }
        return second.to_string();
    }
    first.to_string()
}
